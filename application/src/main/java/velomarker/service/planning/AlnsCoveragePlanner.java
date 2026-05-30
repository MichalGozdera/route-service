package velomarker.service.planning;

import async.TaskCancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static velomarker.service.planning.PlanningOrchestrationService.AreaCandidate;
import static velomarker.service.planning.PlanningOrchestrationService.CoverageBuildInfo;

/**
 * Adaptive Large Neighborhood Search dla coverage planning -- INCREMENTAL BRouter wariant.
 *
 * <p>Bierze GREEDY result jako cieply start, potem iteracyjnie destroy + repair + Simulated
 * Annealing. KAŻDA akceptowana solution jest WALIDOWANA real BRouterem -- cost function bazuje
 * na real km i real loops (gminy odwiedzone wielokrotnie w geometrii).
 *
 * <p>Po ALNS finalSolution jest GOTOWA -- PlanningOrchestrationService POMIJA TRIM/GROW/DEDUP
 * loop, idzie bezposrednio do splitting days. Eliminuje to mieszanie ALNS optymalizacji z
 * lokalna greedy heurystyka.
 *
 * <p>Cost function:
 * <pre>
 *   cost = -coverage_count                                          // max picked (min cost)
 *        + λ_budget × max(0, |realKm - budgetKm| - tolerance)       // budget penalty
 *        + λ_loops × real_redundant_visits                          // pętle penalty
 * </pre>
 *
 * <p>Optymalizacja: proxy gating PRZED BRouter call. Jesli proxy_cost (haversine × roadAreas)
 * znacznie gorszy od current best (> proxySkipThreshold × current), pomin BRouter. Inaczej call.
 */
public class AlnsCoveragePlanner {

    private static final Logger log = LoggerFactory.getLogger(AlnsCoveragePlanner.class);

    private static final double BUDGET_TOLERANCE = 0.05;
    /**
     * Min odleglosc CUMULATIVE-KM w geometrii miedzy "powrotami" do gminy zeby liczyc to jako
     * redundant visit. KM-based zamiast index-based -> niezalezne od dlugosci trasy i gestosci
     * punktow BRouter. 10 km = sensible dla wiekszosci scenariuszy (krotka pętla = nie revisit,
     * powrot z drugiej strony trasy = revisit).
     */
    private static final double REVISIT_KM_GAP = 10.0;
    /**
     * Min odleglosc miedzy waypoints w repair, jako mnoznik sqrt(area_km2). Dla gminy 1km² -> 0.5 km,
     * dla prowincji 100km² -> 5 km. Skala proporcjonalna do wielkosci administrative unit.
     */
    private static final double TOO_CLOSE_SQRT_AREA_FACTOR = 0.5;

    public record AlnsParameters(
            int iterations,
            double destroyRatio,
            double lambdaBudget,
            double lambdaLoops,
            double lambdaBalance,
            double initialTemperatureRatio,
            double coolingRate,
            int noImproveStop,
            double proxySkipThreshold,
            int maxTimeSeconds,
            double lambdaClimb
    ) {}

    /** Wynik optymalizacji ALNS -- gotowy do bezposredniego uzycia w pipeline (pomija TRIM/GROW/DEDUP). */
    public record AlnsResult(
            RouteCalculation calc,
            List<Waypoint> finalWaypoints,
            List<AreaCandidate> picked,
            int iterations,
            int brouterCalls,
            int accepted,
            int rejected,
            int skippedByProxy
    ) {}

    private final AlnsParameters params;
    private final ElevationDataSource elevation;
    private final Random rand;

    public AlnsCoveragePlanner(AlnsParameters params, ElevationDataSource elevation) {
        this.params = params;
        this.elevation = elevation;
        this.rand = new Random(42);
    }

    /**
     * @param brouter funkcja (waypoints -> RouteCalculation) -- wstrzykiwana z PlanningOrchestrationService
     *                bo BRouter chunked jest tam (potrzebuje taskId + profile w closure).
     * @param checkCancel sprawdza czy task został anulowany przed każdym BRouter call.
     */
    public AlnsResult optimize(UUID taskId,
                                CoverageBuildInfo input,
                                RoutePreferences prefs,
                                String profile,
                                RoadFactorCalibrator calibrator,
                                BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                Consumer<UUID> checkCancel) {
        if (input == null || input.pickedCandidates() == null || input.pickedCandidates().isEmpty()) {
            log.info("ALNS: pusty greedy result -- nic do optymalizacji");
            return null;
        }
        long startTs = System.currentTimeMillis();
        long maxMs = params.maxTimeSeconds() * 1000L;

        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : Integer.MAX_VALUE;
        int budgetClimbM = (prefs.days() != null && prefs.elevationPerDayM() != null)
                ? prefs.days() * prefs.elevationPerDayM() : 0;
        // roadAreas z density probe estymuje per-area detour. Ale gdy mamy KLASTER obszarow,
        // BRouter uzywa wspolnych drog -> real detour mniejszy niz suma poszczegolnych. Bedziemy
        // RE-KALIBROWAC po init BRouter (real vs straight).
        double roadAreas = calibrator != null ? calibrator.roadAreas() : 1.3;
        double baselineKm = input.baselineKm() != null ? input.baselineKm() : 0;

        List<AreaCandidate> current = new ArrayList<>(input.pickedCandidates());
        List<AreaCandidate> reserve = input.reserveCandidates() != null
                ? new ArrayList<>(input.reserveCandidates()) : new ArrayList<>();

        // Initial BRouter na greedy solution
        List<Waypoint> wpCurrent = buildWps(current, prefs, input);
        RouteCalculation calcCurrent = brouter.apply(wpCurrent, profile);
        int brouterCalls = 1;

        // RE-KALIBRACJA roadAreas na real BRouter wynik (zamiast density probe estimata).
        // Density probe robi BRouter dla 30 obszarow ODDZIELNIE -> przeszacowuje detour gdy
        // obszary sa w klastrach (real BRouter laczy je przez wspolne drogi). Recalibracja po
        // greedy initial BRouter daje znacznie dokladniejsza proxy dla greedy_insertion.
        double straightDetourSum = 0;
        for (AreaCandidate c : current) {
            if (!c.isIntersected()) straightDetourSum += c.getDetourStraightKm();
        }
        double realExtra = Math.max(0, calcCurrent.distanceKm() - baselineKm);
        if (straightDetourSum > 1.0) {
            double newRoadAreas = realExtra / straightDetourSum;
            log.info("ALNS recalibrate roadAreas: {} -> {} (realExtra={} straightSum={})",
                    new Object[]{String.format("%.3f", roadAreas), String.format("%.3f", newRoadAreas),
                            Math.round(realExtra), Math.round(straightDetourSum)});
            roadAreas = newRoadAreas;
        }

        // CLIMB GATE: jesli initial real climb < 50% target, znaczy ze trasa to NIZINA -- nie
        // ma sensu pchac w gory (gory niedostepne). Disable lambdaClimb dla tego planu.
        // User: "co jak bedzie nizina? bedzie na sile w gory jechal?" -- to chroni przed tym.
        int effectiveBudgetClimbM = budgetClimbM;
        if (budgetClimbM > 0 && elevation != null) {
            try {
                double initialClimb = elevation.sample(calcCurrent.coordinates()).gainM();
                if (initialClimb < budgetClimbM * 0.5) {
                    log.info("ALNS climb gate: initialClimb={} < 50% target={} -- disable climb penalty (nizina)",
                            new Object[]{Math.round(initialClimb), Math.round(budgetClimbM * 0.5)});
                    effectiveBudgetClimbM = 0;
                } else {
                    log.info("ALNS climb gate: initialClimb={} >= 50% target={} -- enable climb penalty",
                            new Object[]{Math.round(initialClimb), Math.round(budgetClimbM * 0.5)});
                }
            } catch (RuntimeException ignored) {}
        }
        final int budgetClimbMFinal = effectiveBudgetClimbM;
        double currentCost = realCost(current, calcCurrent, budgetKm, budgetClimbMFinal);
        RouteCalculation calcBest = calcCurrent;
        List<AreaCandidate> best = new ArrayList<>(current);
        List<Waypoint> wpBest = wpCurrent;
        double bestCost = currentCost;

        double T = Math.max(1.0, Math.abs(bestCost) * params.initialTemperatureRatio());
        int destroyK = Math.max(1, (int) Math.round(current.size() * params.destroyRatio()));

        log.info("ALNS start (real-BRouter): picked={} reserve={} budget={} realKm={} initCost={} T={} k={}",
                new Object[]{current.size(), reserve.size(), budgetKm,
                        Math.round(calcCurrent.distanceKm()),
                        String.format("%.1f", currentCost),
                        String.format("%.1f", T), destroyK});

        int noImprove = 0;
        int accepted = 0;
        int rejected = 0;
        int skipped = 0;

        for (int iter = 0; iter < params.iterations(); iter++) {
            try { checkCancel.accept(taskId); }
            catch (TaskCancellationException tce) {
                log.info("ALNS cancelled at iter={}", iter);
                throw tce;
            }
            if (System.currentTimeMillis() - startTs > maxMs) {
                log.warn("ALNS hit max-time-seconds ({}) at iter={} bestCost={} brouterCalls={}",
                        new Object[]{params.maxTimeSeconds(), iter,
                                String.format("%.1f", bestCost), brouterCalls});
                break;
            }

            // DESTROY + REPAIR
            List<AreaCandidate> candidate = new ArrayList<>(current);
            List<AreaCandidate> destroyed;
            int op = rand.nextInt(3);
            if (op == 0) destroyed = randomRemoval(candidate, destroyK);
            else if (op == 1) destroyed = worstRemoval(candidate, destroyK);
            else destroyed = clusterRemoval(candidate, destroyK);

            List<AreaCandidate> repairPool = new ArrayList<>(reserve);
            repairPool.addAll(destroyed);
            // Boost target gdy current solution jest underBudget -- pompujemy do realnego budżetu.
            // ratio = real/budget. Jesli realKm=1143 budget=1800 -> ratio=0.635 -> boost=1/0.635*0.97=1.53
            // Target proxy 1.53 × budget -> BRouter da ~budget × 0.97.
            double realRatio = Math.max(0.5, calcCurrent.distanceKm() / Math.max(1, budgetKm));
            double boost = realRatio < 0.95 ? Math.min(1.5, 0.97 / realRatio) : 0.97;
            greedyInsertion(candidate, repairPool, budgetKm, baselineKm, roadAreas, boost);

            // PROXY GATING -- if proxy znacznie gorszy niz best, skip real BRouter
            double proxyCost = proxyCost(candidate, budgetKm, baselineKm, roadAreas);
            double proxyBest = proxyCost(best, budgetKm, baselineKm, roadAreas);
            if (proxyBest > 0 && proxyCost > params.proxySkipThreshold() * Math.abs(proxyBest)) {
                skipped++;
                continue;
            }

            // REAL BRouter
            List<Waypoint> wpCandidate = buildWps(candidate, prefs, input);
            RouteCalculation calcCandidate;
            try {
                calcCandidate = brouter.apply(wpCandidate, profile);
            } catch (RuntimeException e) {
                log.debug("BRouter failed for candidate (iter={}): {}", new Object[]{iter, e.getMessage()});
                rejected++;
                continue;
            }
            brouterCalls++;
            int candidateLoops = countRedundantVisitsFromGeometry(candidate, calcCandidate.coordinates());
            double newCost = realCost(candidate, calcCandidate, budgetKm, budgetClimbMFinal);
            // Diagnostyka: pokaz effort metric (km + climb × 0.1)
            double cClimb = 0;
            if (elevation != null) {
                try { cClimb = elevation.sample(calcCandidate.coordinates()).gainM(); }
                catch (RuntimeException ignored) {}
            }
            double cEffort = calcCandidate.distanceKm() + cClimb * 0.1;
            log.info("ALNS iter={} candidate: picked={} realKm={} climb={} effortKm={} loops={} cost={} {}",
                    new Object[]{iter, candidate.size(),
                            Math.round(calcCandidate.distanceKm()),
                            Math.round(cClimb),
                            Math.round(cEffort),
                            candidateLoops, String.format("%.1f", newCost),
                            newCost < bestCost ? "<<NEW BEST>>" : ""});

            if (newCost < bestCost) {
                best = new ArrayList<>(candidate);
                wpBest = wpCandidate;
                calcBest = calcCandidate;
                bestCost = newCost;
                noImprove = 0;
            } else {
                noImprove++;
            }

            if (acceptSA(newCost, currentCost, T)) {
                current = candidate;
                calcCurrent = calcCandidate;
                currentCost = newCost;
                accepted++;
                reserve.removeAll(current);
                for (AreaCandidate d : destroyed) {
                    if (!current.contains(d) && !reserve.contains(d)) reserve.add(d);
                }
            } else {
                rejected++;
            }

            T *= params.coolingRate();

            if (noImprove >= params.noImproveStop()) {
                log.info("ALNS no-improve stop at iter={} (noImprove={})",
                        new Object[]{iter, noImprove});
                break;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTs;
        log.info("ALNS done: bestCost={} bestPicked={} bestKm={} (greedy was {} pick) brouterCalls={} accepted={} rejected={} skipped={} elapsedMs={}",
                new Object[]{String.format("%.1f", bestCost), best.size(),
                        Math.round(calcBest.distanceKm()),
                        input.pickedCandidates().size(),
                        brouterCalls, accepted, rejected, skipped, elapsedMs});

        // POST-ALNS DEDUP: usun z best te obszary ktore trasa BRouter naturalnie zalicza
        // (geometria przechodzi przez ich ring). Te NIE potrzebuja wlasnego entry-pointu --
        // dodatkowy waypoint powoduje objazd. Po dedup re-run BRouter na lighter waypoints.
        // User: "powinien pomijac obszary juz uprzednio jechane, nie tylko te od ktorych zaczynamy".
        int beforeDedup = best.size();
        List<AreaCandidate> bestCopy = new ArrayList<>(best);
        PlanningOrchestrationService.removeNaturallyCoveredFromPicked(bestCopy, calcBest.coordinates());
        if (bestCopy.size() < beforeDedup) {
            log.info("ALNS post-dedup: {} -> {} (naturally covered by route)",
                    new Object[]{beforeDedup, bestCopy.size()});
            List<Waypoint> wpDedup = buildWps(bestCopy, prefs, input);
            try {
                RouteCalculation calcDedup = brouter.apply(wpDedup, profile);
                brouterCalls++;
                double dedupCost = realCost(bestCopy, calcDedup, budgetKm, budgetClimbMFinal);
                log.info("ALNS post-dedup result: realKm={} cost={} (best was {})",
                        new Object[]{Math.round(calcDedup.distanceKm()),
                                String.format("%.1f", dedupCost), String.format("%.1f", bestCost)});
                // Akceptuj dedup result jesli cost rownie dobry lub lepszy (less coverage minus, ale mniej petli)
                if (dedupCost <= bestCost + Math.abs(bestCost) * 0.05) {
                    best = bestCopy;
                    wpBest = wpDedup;
                    calcBest = calcDedup;
                    bestCost = dedupCost;
                }
            } catch (RuntimeException e) {
                log.warn("ALNS post-dedup BRouter failed: {} -- keeping pre-dedup best", e.getMessage());
            }
        }

        // Iter 9: POST-BRouter DUPLICATE-VISIT DEDUP (wspólny helper). Eliminuje obszary
        // odwiedzane 2× w finalnej geometrii BRouter. Tour modyfikowany in-place.
        java.util.Set<String> alnsAnchors = new java.util.HashSet<>();
        if (prefs.start() != null && prefs.start().name() != null) alnsAnchors.add(prefs.start().name());
        if (prefs.end() != null && prefs.end().name() != null) alnsAnchors.add(prefs.end().name());
        if (prefs.via() != null) for (var v : prefs.via()) if (v.name() != null) alnsAnchors.add(v.name());
        java.util.List<Waypoint> mutableWpBest = new ArrayList<>(wpBest);
        RouteCalculation dedupedCalc = removeDuplicateVisitWaypoints(
                best, mutableWpBest, calcBest, profile, brouter, alnsAnchors);
        if (mutableWpBest.size() < wpBest.size()) {
            wpBest = mutableWpBest;
            calcBest = dedupedCalc;
        }

        return new AlnsResult(calcBest, wpBest, best, params.iterations(), brouterCalls,
                accepted, rejected, skipped);
    }

    // ── COST: REAL ──────────────────────────────────────────────────────────────────────────

    /**
     * Cost: COMBINED EFFORT metric (km + climb × 0.1) zamiast oddzielnych penalty.
     *
     * <p>User: "nie lepiej obliczyc na starcie ze skoro user daje nam 1800km i 21370m wzniosu
     * to zamienic ten wznios na dodatkowe kilometry i wyjdzie dodatkowe okolo 2100km zupelnie
     * po plaskim?". Bingo. Zamiast karac za km violation I climb undershoot oddzielnie, sumujemy
     * w 1 metryke effort_km = realKm + realClimbM × 0.1. Budget effort = budgetKm + budgetClimb × 0.1.
     *
     * <p>Wtedy ALNS porownuje rozwiazania uczciwie: trasa 2095km/9800m climb = 3075 effort jest
     * LEPSZA niz 1802km/9900m = 2792 effort (bliej budget_effort=3937).
     *
     * <p>0.1 km/m = 30km kary za 300m wzniosu (z formuly road-uphill).
     */
    double realCost(List<AreaCandidate> picked, RouteCalculation calc, int budgetKm, int budgetClimbM) {
        int coverage = picked.size();
        if (coverage == 0) return Double.MAX_VALUE;
        double realKm = calc.distanceKm();
        double realClimbM = 0;
        if (elevation != null) {
            try {
                realClimbM = elevation.sample(calc.coordinates()).gainM();
            } catch (RuntimeException ignored) {}
        }
        // Combined effort metric: km + climb × 0.1 (= 30km / 300m wzniosu).
        final double CLIMB_TO_KM = 0.1;
        double effortKm = realKm + realClimbM * CLIMB_TO_KM;
        double budgetEffort = budgetKm + budgetClimbM * CLIMB_TO_KM;
        double effortTol = budgetEffort * BUDGET_TOLERANCE;
        // Symmetric violation: kara zarowno za overshoot (za dluga lub za gorsista) jak undershoot.
        double effortViolation = Math.max(0, Math.abs(effortKm - budgetEffort) - effortTol);
        double effortViolationFraction = budgetEffort > 0 ? effortViolation / budgetEffort : 0;
        int redundant = countRedundantVisitsFromGeometry(picked, calc.coordinates());
        double sqrtCoverage = Math.sqrt(coverage);
        // λ_budget steruje effort penalty (km + climb razem). λ_climb deprecated ale zostawiamy
        // jako kompatybilnosc -- ignorowane w nowym formula.
        return -coverage
                + params.lambdaBudget() * effortViolationFraction * coverage
                + params.lambdaLoops() * redundant / sqrtCoverage;
    }

    /**
     * Liczy ile picked obszarow ma "powroty" do siebie z gap > REVISIT_KM_GAP w cumulativeKm
     * geometrii. KM-based gap = niezalezne od dlugosci trasy i gestosci punktow BRouter.
     */
    static int countRedundantVisitsFromGeometry(List<AreaCandidate> picked, List<double[]> geometry) {
        if (geometry == null || geometry.size() < 2) return 0;
        // Pre-compute cumulative km dla geometrii (1 przebieg, O(n))
        double[] cumKm = new double[geometry.size()];
        for (int i = 1; i < geometry.size(); i++) {
            cumKm[i] = cumKm[i - 1] + WaypointSelector.haversineKm(geometry.get(i - 1), geometry.get(i));
        }
        int redundant = 0;
        for (AreaCandidate c : picked) {
            double[][] ring = c.getArea().ring();
            if (ring == null || ring.length < 3) continue;
            double prevHitKm = Double.NEGATIVE_INFINITY;
            int hits = 0;
            for (int i = 0; i < geometry.size(); i++) {
                if (WaypointSelector.pointInRing(geometry.get(i), ring)) {
                    if (cumKm[i] - prevHitKm > REVISIT_KM_GAP) {
                        hits++;
                    }
                    prevHitKm = cumKm[i];
                }
            }
            if (hits > 1) redundant += (hits - 1);
        }
        return redundant;
    }

    /**
     * Iter 9: rozszerzenie countRedundantVisitsFromGeometry — zwraca MAP per-area z liczbą
     * odrębnych wizyt. Wizyty = ciągłe segmenty geometrii w ringu, oddzielone gap > REVISIT_KM_GAP.
     * User: "po kazdej iteracji brac to co wyznaczyl brouter i wylapywac duplikaty".
     */
    public static java.util.Map<AreaCandidate, Integer> countVisitsPerArea(
            List<AreaCandidate> picked, List<double[]> geometry) {
        java.util.Map<AreaCandidate, Integer> hits = new java.util.HashMap<>();
        if (geometry == null || geometry.size() < 2) {
            for (AreaCandidate c : picked) hits.put(c, 0);
            return hits;
        }
        double[] cumKm = new double[geometry.size()];
        for (int i = 1; i < geometry.size(); i++) {
            cumKm[i] = cumKm[i - 1] + WaypointSelector.haversineKm(geometry.get(i - 1), geometry.get(i));
        }
        for (AreaCandidate c : picked) {
            double[][] ring = c.getArea().ring();
            if (ring == null || ring.length < 3) { hits.put(c, 0); continue; }
            double prev = Double.NEGATIVE_INFINITY;
            int count = 0;
            for (int i = 0; i < geometry.size(); i++) {
                if (WaypointSelector.pointInRing(geometry.get(i), ring)) {
                    if (cumKm[i] - prev > REVISIT_KM_GAP) count++;
                    prev = cumKm[i];
                }
            }
            hits.put(c, count);
        }
        return hits;
    }

    /**
     * Iter 9: post-BRouter duplicate-visit dedup. Iteruje aż brak obszarów z >1 wizytą lub
     * MAX_DEDUP_ITER. Per iter:
     *  1. Liczy visits per area
     *  2. Identyfikuje areas z hits > 1 (NIE intersected — te są darmowe, nie usuwamy)
     *  3. Usuwa wszystkie waypointy z tour z odpowiednią nazwą (tour idzie naturalnie)
     *  4. Re-run BRouter na zmniejszonym tour
     *
     * <p>WAŻNE: NIE usuwamy z `picked` (area zostaje zaliczona przez naturalną geometrię),
     * tylko explicit waypoint z tour. Coverage zachowany.
     *
     * @return new RouteCalculation po dedup (lub original gdy brak duplikatów / BRouter failure)
     */
    public static RouteCalculation removeDuplicateVisitWaypoints(
            List<AreaCandidate> picked,
            List<Waypoint> tour,
            RouteCalculation currentCalc,
            String profile,
            java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
            java.util.Set<String> anchorNames) {
        final int MAX_DEDUP_ITER = 3;
        RouteCalculation calc = currentCalc;
        int totalDropped = 0;
        for (int iter = 0; iter < MAX_DEDUP_ITER; iter++) {
            java.util.Map<AreaCandidate, Integer> visits = countVisitsPerArea(picked, calc.coordinates());
            java.util.Set<String> namesToRemove = new java.util.HashSet<>();
            int areasWithDup = 0;
            for (var e : visits.entrySet()) {
                if (e.getValue() > 1 && !e.getKey().isIntersected()) {
                    namesToRemove.add(e.getKey().getArea().name());
                    areasWithDup++;
                }
            }
            if (namesToRemove.isEmpty()) {
                if (iter == 0) log.debug("Dup-visit dedup: 0 areas with 2+ visits, skip");
                break;
            }
            // Drop waypointy z tour (z wyjątkiem anchorów). Area zostaje w `picked`.
            int beforeWp = tour.size();
            tour.removeIf(w -> w.name() != null
                    && namesToRemove.contains(w.name())
                    && (anchorNames == null || !anchorNames.contains(w.name())));
            int dropped = beforeWp - tour.size();
            if (dropped == 0) break;
            totalDropped += dropped;
            double oldKm = calc.distanceKm();
            try {
                calc = brouter.apply(tour, profile);
                log.info("Dup-visit dedup iter {}: {} areas with 2+ hits → dropped {} wp, realKm {} → {}",
                        new Object[]{iter, areasWithDup, dropped, Math.round(oldKm),
                                Math.round(calc.distanceKm())});
            } catch (RuntimeException e) {
                log.warn("Dup-visit dedup BRouter failed at iter {}: {} -- zostaje pre-dedup calc",
                        new Object[]{iter, e.getMessage()});
                break;
            }
        }
        if (totalDropped > 0) {
            log.info("Dup-visit dedup TOTAL: dropped {} wp", totalDropped);
        }
        return calc;
    }

    // ── COST: PROXY (cheap gating before BRouter) ───────────────────────────────────────────

    double proxyCost(List<AreaCandidate> picked, int budgetKm, double baselineKm, double roadAreas) {
        int coverage = picked.size();
        if (coverage == 0) return Double.MAX_VALUE;
        double estKm = baselineKm;
        for (AreaCandidate c : picked) {
            if (!c.isIntersected()) estKm += c.getDetourStraightKm() * roadAreas;
        }
        double budgetTol = budgetKm * BUDGET_TOLERANCE;
        double violationKm = Math.max(0, Math.abs(estKm - budgetKm) - budgetTol);
        double violationFraction = budgetKm > 0 ? violationKm / budgetKm : 0;
        return -coverage + params.lambdaBudget() * violationFraction * coverage;
    }

    // ── DESTROY OPERATORS ───────────────────────────────────────────────────────────────────

    List<AreaCandidate> randomRemoval(List<AreaCandidate> picked, int k) {
        List<AreaCandidate> removed = new ArrayList<>(k);
        for (int i = 0; i < k && !picked.isEmpty(); i++) {
            int idx = rand.nextInt(picked.size());
            removed.add(picked.remove(idx));
        }
        return removed;
    }

    List<AreaCandidate> worstRemoval(List<AreaCandidate> picked, int k) {
        List<AreaCandidate> sortedDesc = new ArrayList<>(picked);
        sortedDesc.sort((a, b) -> Double.compare(b.getDetourStraightKm(), a.getDetourStraightKm()));
        List<AreaCandidate> removed = new ArrayList<>();
        for (int i = 0; i < Math.min(k, sortedDesc.size()); i++) {
            AreaCandidate r = sortedDesc.get(i);
            picked.remove(r);
            removed.add(r);
        }
        return removed;
    }

    /** Wybiera random pivot, usuwa k najbliższych geograficznie -- usuwa CAŁY klaster. */
    List<AreaCandidate> clusterRemoval(List<AreaCandidate> picked, int k) {
        if (picked.isEmpty()) return new ArrayList<>();
        AreaCandidate pivot = picked.get(rand.nextInt(picked.size()));
        double[] pp = new double[]{pivot.getArea().lng(), pivot.getArea().lat()};
        List<AreaCandidate> sortedByDist = new ArrayList<>(picked);
        sortedByDist.sort((a, b) -> {
            double da = WaypointSelector.haversineKm(pp,
                    new double[]{a.getArea().lng(), a.getArea().lat()});
            double db = WaypointSelector.haversineKm(pp,
                    new double[]{b.getArea().lng(), b.getArea().lat()});
            return Double.compare(da, db);
        });
        List<AreaCandidate> removed = new ArrayList<>();
        for (int i = 0; i < Math.min(k, sortedByDist.size()); i++) {
            AreaCandidate r = sortedByDist.get(i);
            picked.remove(r);
            removed.add(r);
        }
        return removed;
    }

    // ── REPAIR ──────────────────────────────────────────────────────────────────────────────

    /**
     * Greedy insertion: sortuj repairPool po detour ASC, dorzucaj az estKm osiagnie targetKm.
     * Skip jesli juz mamy podobny obszar blisko.
     *
     * @param boostUnderBudget jesli > 1.0, target zwiekszany (uzywane gdy lastRealKm znacznie ponizej budget)
     */
    void greedyInsertion(List<AreaCandidate> picked, List<AreaCandidate> repairPool,
                         int budgetKm, double baselineKm, double roadAreas,
                         double boostUnderBudget) {
        // Iter 8: SHELL EXPANSION (zamiast global sort po detour). User: "ma zostac grow shell".
        // Reuse TSP logiki: shell radius 10/20/30 km × area_scale (sqrt(areaKm²)/6).
        // Cheapest insertion w aktualnym shell, dopóki budget niewyczerpany.
        final double SHELL_START_KM = 10.0;
        final double SHELL_STEP_KM = 10.0;
        final double SHELL_MAX_KM = 100.0;
        double targetKm = budgetKm * boostUnderBudget;
        double currentKm = baselineKm;
        Set<AreaCandidate> already = new HashSet<>(picked);
        for (AreaCandidate c : picked) {
            if (!c.isIntersected()) currentKm += c.getDetourStraightKm() * roadAreas;
        }
        // Sort pool ASC po detour (cheapest insertion priority w ramach shell)
        List<AreaCandidate> sorted = new ArrayList<>(repairPool);
        sorted.sort((a, b) -> Double.compare(a.getDetourStraightKm(), b.getDetourStraightKm()));

        double shellKm = SHELL_START_KM;
        while (shellKm <= SHELL_MAX_KM && currentKm < targetKm) {
            for (AreaCandidate c : sorted) {
                if (already.contains(c)) continue;
                if (currentKm >= targetKm) break;
                // Per-area shell scaling: większe obszary tolerują szerszy promień
                double areaScale = Math.max(1.0,
                        Math.sqrt(Math.max(0, c.getArea().areaKm2())) / 6.0);
                if (c.getDistToBaselineKm() > shellKm * areaScale) continue;
                double detourReal = c.isIntersected() ? 0 : c.getDetourStraightKm() * roadAreas;
                if (currentKm + detourReal > targetKm && !c.isIntersected()) continue;
                if (tooCloseToPicked(c, picked)) continue;
                picked.add(c);
                already.add(c);
                currentKm += detourReal;
            }
            shellKm += SHELL_STEP_KM;
        }
    }

    /**
     * Dwa obszary uznajemy za "za blisko siebie" gdy distance(centroidow) < sqrt(area_km2) × 0.5
     * MEAN obu. Dla gminy 1km² (CZ obec) -> ~0.5 km, dla powiatu 600km² (CZ okres) -> ~12 km,
     * dla prowincji 5000km² (IT regione) -> ~35 km. Skaluje sie z administrative unit size.
     */
    static boolean tooCloseToPicked(AreaCandidate c, List<AreaCandidate> picked) {
        double[] pc = new double[]{c.getArea().lng(), c.getArea().lat()};
        double cArea = c.getArea().areaKm2();
        double cRadius = cArea > 0 ? Math.sqrt(cArea) * TOO_CLOSE_SQRT_AREA_FACTOR : 1.5;
        for (AreaCandidate p : picked) {
            double[] pp = new double[]{p.getArea().lng(), p.getArea().lat()};
            double pArea = p.getArea().areaKm2();
            double pRadius = pArea > 0 ? Math.sqrt(pArea) * TOO_CLOSE_SQRT_AREA_FACTOR : 1.5;
            // Wymagana min separacja = srednia z dwoch (bo oba majaszą wielkosc)
            double minSep = (cRadius + pRadius) / 2.0;
            if (WaypointSelector.haversineKm(pc, pp) < minSep) return true;
        }
        return false;
    }

    // ── SA ──────────────────────────────────────────────────────────────────────────────────

    boolean acceptSA(double newCost, double currentCost, double T) {
        if (newCost < currentCost) return true;
        if (T <= 0) return false;
        double prob = Math.exp(-(newCost - currentCost) / T);
        return rand.nextDouble() < prob;
    }

    // ── HELPER ──────────────────────────────────────────────────────────────────────────────

    static List<Waypoint> buildWps(List<AreaCandidate> picked, RoutePreferences prefs, CoverageBuildInfo input) {
        return PlanningOrchestrationService.buildWaypointsFromPicked(prefs,
                PlanningOrchestrationService.sortByInsertionIdx(new ArrayList<>(picked)),
                input.baselineGeometry());
    }
}
