package velomarker.service.planning.tsp;

import async.TaskCancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;
import velomarker.service.planning.PlanningOrchestrationService;
import velomarker.service.planning.PlanningOrchestrationService.AreaCandidate;
import velomarker.service.planning.PlanningOrchestrationService.CoverageBuildInfo;
import velomarker.service.planning.RoadFactorCalibrator;
import velomarker.service.planning.WaypointSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TSP-based cheapest insertion z spatial grid index.
 *
 * <p>User: "powłóczy się po okolicy zgarnac co po drodze". Algorytm:
 * <ol>
 *   <li>Init tour = [start, via..., end] (anchors only)</li>
 *   <li>Iteruj: dla każdej tour edge -- znajdz cheapest insertion z grid index</li>
 *   <li>Wstaw najtanszego do tour</li>
 *   <li>Co N=10 inserts -- real BRouter recalibracja roadAreas factor</li>
 *   <li>Stop gdy zaden candidate nie miesci sie w budzecie (real, nie proxy)</li>
 *   <li>Final BRouter na pelnym tour</li>
 * </ol>
 *
 * <p>Spatial grid: per edge query tylko sasiednie komorki (~30-100 gmin zamiast 2000).
 * Complexity: O(P × edges × M) gdzie M ~ 30. Dla P=200, edges=200, M=30 -> ~1.2M ops.
 *
 * <p>Ring touch (user: "nie musisz dojezdzac do centrum") -- zostaje na PR#2, na razie MVP.
 */
public class TspCoveragePlanner {

    private static final Logger log = LoggerFactory.getLogger(TspCoveragePlanner.class);

    /** Buffer od edge w ktorym szukamy candidates. Dla GROW phase x2 (bo short edges). */
    private static final double EDGE_BUFFER_KM = 50.0;
    private static final double GROW_EDGE_BUFFER_KM = 80.0;
    /** Co ile inserts robic real BRouter recalibracja. */
    private static final int RECALIBRATE_EVERY = 15;
    /** Cell size grid w stopniach (~11km). */
    private static final double GRID_CELL_DEG = 0.1;
    /**
     * Baseline-corridor penalty: λ × distToBaselineKm dodane do effectiveCost.
     * 0.20 było za słabe -- algorytm wciąż jeździł 200 km na południe. 0.60 = obszar 50 km od
     * korytarza kosztuje +30 km (real-km equivalent). Wymusza pozostanie blisko start→end line.
     */
    private static final double BASELINE_CORRIDOR_PENALTY = 0.60;
    /**
     * Climb-aware GROW: TYLKO gdy deficyt wzniosu jest UMIARKOWANY (climbRatio ≥ 0.5).
     * Przy climbRatio < 0.5 region jest fundamentalnie płaski (np. Morawy w czechach) -- nie da
     * się dorobić 19000m wzniosu kilometrami w tym samym regionie, próba kończy się balonem
     * trasy 3700 km / 1800 budget (poprzednia katastrofa).
     */
    private static final double CLIMB_DEFICIT_TRIGGER = 0.85;
    /**
     * Iter 9: USUNIĘTO MIN threshold (poprzednio 0.20). User: "zawsze dorzucaj gdy zostanie
     * [wzniosu], nawet na czyms co uwazasz ze plaskie". Extension ZAWSZE odpalany gdy predicted
     * climbRatio < 0.85. Cap 30% chroni przed balonem.
     */
    /** Climb-overshoot trigger: gdy predicted climbRatio > 1.15 → shrink km budget. */
    private static final double CLIMB_OVERSHOOT_TRIGGER = 1.15;
    /**
     * Cap extension na 5% km budgetu. User: "zmniejsz cap na 5%, on dobrze zamienial brak gór
     * na wysilek na plaskim". Pre-GROW + post-check łącznie nie przekraczają 1.05× budget.
     */
    private static final double CLIMB_EXTENSION_MAX_RATIO = 0.05;
    /** Cap shrink na 20% km budgetu (max -360 km dla 1800). */
    private static final double CLIMB_SHRINK_MAX_RATIO = 0.20;
    /** Profile factor: ROAD 300m=20km (0.067 km/m), OFFROAD 300m=30km (0.10 km/m). */
    private static final double ROAD_KM_PER_M_CLIMB = 20.0 / 300.0;     // ~0.0667
    private static final double OFFROAD_KM_PER_M_CLIMB = 30.0 / 300.0;  // 0.10
    /** TRIM-GROW konwergencja: max cykli i [budget × min, budget × max] = strefa OK. */
    private static final int MAX_TRIM_GROW_CYCLES = 3;
    private static final double TRIM_TRIGGER = 1.05;  // realKm > 105% effBudget => trim
    private static final double GROW_TRIGGER = 0.92;  // realKm < 92%  effBudget => grow

    /**
     * Shell expansion w GROW: zaczynamy od bufora SHELL_START km od baseline, rozszerzamy o
     * SHELL_STEP km dopóki budget niewyczerpany. Max SHELL_MAX km — dalej trasa nie idzie nawet
     * jeśli budget pozostaje. User: "rozszerzal promien po prostu, albo zmniejszal jak nie styknie".
     */
    private static final double SHELL_START_KM = 10.0;
    private static final double SHELL_STEP_KM = 10.0;
    /** Iter 9 Fix #2: 100 → 200 km. Pozwala shell expansion sięgać do bbox limit (200 km). */
    private static final double SHELL_MAX_KM = 200.0;

    /** Wynik TSP -- zostawiamy taki sam shape jak ALNS żeby integrate latwo. */
    public record TspResult(
            RouteCalculation calc,
            List<Waypoint> finalWaypoints,
            List<AreaCandidate> picked,
            int inserts,
            int brouterCalls,
            int rejectedByBudget
    ) {}

    public TspResult plan(UUID taskId,
                           CoverageBuildInfo input,
                           RoutePreferences prefs,
                           String profile,
                           RoadFactorCalibrator calibrator,
                           BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                           BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal,
                           Consumer<UUID> checkCancel) {
        return plan(taskId, input, prefs, profile, calibrator, brouter, brouterFinal, checkCancel, null);
    }

    /**
     * @param elevationGainFn opcjonalny callback (geometria → gainM). Gdy podany, GROW phase
     *        sprawdza deficyt wzniosu po pierwszej fazie i rozszerza km budget żeby zamknąć
     *        lukę (anti-elevation-waste). Bez tego: brak climb-awareness, możliwy duży zapas
     *        wzniosu nigdy nieskonsumowany.
     */
    public TspResult plan(UUID taskId,
                           CoverageBuildInfo input,
                           RoutePreferences prefs,
                           String profile,
                           RoadFactorCalibrator calibrator,
                           BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                           BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal,
                           Consumer<UUID> checkCancel,
                           Function<List<double[]>, Integer> elevationGainFn) {
        long startTs = System.currentTimeMillis();
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : Integer.MAX_VALUE;
        double roadAreas = calibrator != null ? calibrator.roadAreas() : 1.3;
        double baselineKm = input.baselineKm() != null ? input.baselineKm() : 0;

        // HYBRID: greedy zrobil selekcje (mutual dedup, density-aware, area-weighted picking,
        // EMA road factor) w buildCoverageWaypointsWithInfo. TSP startuje z TYMI 67 wp jako
        // initial tour, robi tylko 2-opt + post-dedup + TRIM↔GROW konwergencja.
        //
        // Wczesniej TSP budowal od zera cheapest insertion z `pickedCandidates + reserveCandidates`
        // (2016 pula). Reserve NIE przeszlo greedy's mutual dedup -> klastrowe petle (46 wp na
        // 80 km, slalom miedzy sasiednimi Obec). Po hybrid pula initial = greedy's 67 picked.
        SpatialAreaIndex grid = new SpatialAreaIndex(GRID_CELL_DEG);
        List<AreaCandidate> allCandidates = new ArrayList<>();
        if (input.pickedCandidates() != null) allCandidates.addAll(input.pickedCandidates());
        if (input.reserveCandidates() != null) allCandidates.addAll(input.reserveCandidates());
        grid.addAll(allCandidates);

        // Initial tour = [start, via..., GREEDY's non-intersected picked sorted by insertionIdx, end].
        // intersected obszary sa "darmowe" (baseline juz je przecina) -- NIE dodajemy waypointu,
        // ale wpisujemy do `picked` zeby raportowac jako zaliczone.
        List<Waypoint> tour = new ArrayList<>();
        tour.add(prefs.start());
        if (prefs.via() != null) tour.addAll(prefs.via());
        List<AreaCandidate> picked = new ArrayList<>();
        long intersectedCount = 0;
        long mutuallyCoveredCount = 0;
        if (input.pickedCandidates() != null) {
            // intersected = picked-but-no-waypoint
            for (AreaCandidate c : input.pickedCandidates()) {
                if (c.isIntersected()) {
                    picked.add(c);
                    grid.markPicked(c);
                    intersectedCount++;
                }
            }
            // Iter 9 Fix #1: mutually-covered = picked-but-no-waypoint (trasa naturalnie przez nie idzie)
            for (AreaCandidate c : input.pickedCandidates()) {
                if (!c.isIntersected() && c.isMutuallyCoveredByNeighbor()) {
                    picked.add(c);
                    grid.markPicked(c);
                    mutuallyCoveredCount++;
                }
            }
            // non-intersected, non-mutually-covered = entry-point w tour, sortowane po insertionIdx
            List<AreaCandidate> sortedByIdx = input.pickedCandidates().stream()
                    .filter(c -> !c.isIntersected() && !c.isMutuallyCoveredByNeighbor())
                    .sorted(java.util.Comparator.comparingInt(AreaCandidate::getInsertionIdx))
                    .toList();
            for (AreaCandidate c : sortedByIdx) {
                tour.add(new Waypoint(c.getEntryLng(), c.getEntryLat(), c.getArea().name()));
                picked.add(c);
                grid.markPicked(c);
            }
        }
        // end
        if (Boolean.TRUE.equals(prefs.loop())) {
            tour.add(prefs.start());
        } else if (prefs.end() != null) {
            tour.add(prefs.end());
        } else {
            tour.add(prefs.start());
        }
        log.info("TSP init (hybrid): grid loaded {} candidates, budget={} km, baselineKm={}, greedy picked={} (intersected={}, mutually-covered={}, with-waypoint={})",
                new Object[]{allCandidates.size(), budgetKm, Math.round(baselineKm),
                        picked.size(), intersectedCount, mutuallyCoveredCount,
                        picked.size() - intersectedCount - mutuallyCoveredCount});

        int brouterCalls = 0;
        int rejectedByBudget = 0;
        int inserts = 0;  // hybrid: 0 phase-1 inserts (greedy zrobil selekcje)

        // 2-OPT LOCAL SEARCH: eliminuje "wracanie ta sama droga" przez reverse segmentow tour.
        // User: "pojechales do chemnitz i wrociles ta sama droga, nie przez annaberg bucholz".
        // 2-opt znajdzie ze reverse segmentu [chemnitz..wpA] daje krotsza trase i naturalnie
        // wprowadzi loop przez sasiednie kreissitz.
        java.util.Set<String> anchorNames = collectAnchorNames(prefs);
        int swapsApplied = apply2Opt(tour, anchorNames);
        if (swapsApplied > 0) {
            log.info("TSP 2-opt: {} swaps applied (better tour order)", swapsApplied);
        }

        // Final BRouter
        RouteCalculation finalCalc;
        try {
            finalCalc = brouter.apply(tour, profile);
            brouterCalls++;
        } catch (RuntimeException e) {
            log.error("TSP final BRouter failed: {}", e.getMessage());
            throw e;
        }

        // POST-TSP DEDUP (RING-INTERSECTION, turf-like): jesli trasa NATURALNIE wchodzi w ring obszaru
        // (point-in-ring po pelnej geometrii BRoutera z bbox prefilter), entry-point waypoint jest
        // zbedny -- drop. Bez haversine threshold (uniwersalne, dziala dla Obec 30 km² i Provincia
        // 5000 km² tak samo).
        //
        // User: "2 razy wjezdza do tej samej gminy" -- gdy trasa wraca w ring obszaru z innej strony,
        // explicit entry-point tworzy zbedny detour. Ring-intersection check po PELNEJ geometrii to
        // wykryje (nie tylko ±300 punktow wokol centroidu, jak isAreaNaturallyCovered).
        int beforeDedup = tour.size();
        java.util.Set<Integer> dedupRemoved = new java.util.HashSet<>();
        List<Waypoint> tourAfterDedup = new ArrayList<>();
        for (int i = 0; i < tour.size(); i++) {
            Waypoint wp = tour.get(i);
            if (anchorNames.contains(wp.name())) {
                tourAfterDedup.add(wp);
                continue;
            }
            AreaCandidate area = picked.stream()
                    .filter(c -> wp.name() != null && wp.name().equals(c.getArea().name()))
                    .filter(c -> !c.isIntersected())
                    .findFirst().orElse(null);
            if (area == null) {
                tourAfterDedup.add(wp);
                continue;
            }
            if (isRingTouchedByRoute(area, finalCalc.coordinates())) {
                dedupRemoved.add(area.getArea().areaId());
            } else {
                tourAfterDedup.add(wp);
            }
        }
        if (!dedupRemoved.isEmpty()) {
            log.info("TSP post-dedup: kandyduje usunac {} entry-points (naturalnie pokryte), tour {} -> {} wp",
                    new Object[]{dedupRemoved.size(), beforeDedup, tourAfterDedup.size()});
            try {
                RouteCalculation dedupCalc = brouter.apply(tourAfterDedup, profile);
                brouterCalls++;
                // VALIDATE: sprawdz ile picked nadal jest ring-touched przez NEW geometry.
                // Krotsza trasa po dedup moze isc INNA droga i nie zaliczyc obszarow ktore
                // byly naturally covered W STAREJ geometrii. Akceptuj tylko jesli >= 90% picked
                // jest nadal ring-touched lub realKm tylko nieznacznie krotsza (< 10% diff).
                long stillCovered = picked.stream()
                        .filter(c -> c.isIntersected() || isRingTouchedByRoute(c, dedupCalc.coordinates()))
                        .count();
                double coverageRatio = (double) stillCovered / picked.size();
                double kmDropRatio = (finalCalc.distanceKm() - dedupCalc.distanceKm()) / Math.max(1, finalCalc.distanceKm());
                if (coverageRatio >= 0.90) {
                    log.info("TSP post-dedup accepted: realKm {} -> {}, coverage {}% nadal pokryte",
                            new Object[]{Math.round(finalCalc.distanceKm()), Math.round(dedupCalc.distanceKm()),
                                    Math.round(coverageRatio * 100)});
                    finalCalc = dedupCalc;
                    tour = tourAfterDedup;
                } else {
                    log.warn("TSP post-dedup REJECTED: krotsza trasa traci coverage -- tylko {}% picked nadal ring-touched (kmDrop={}%)",
                            new Object[]{Math.round(coverageRatio * 100), Math.round(kmDropRatio * 100)});
                }
            } catch (RuntimeException e) {
                log.warn("TSP post-dedup BRouter failed: {}", e.getMessage());
            }
        }

        // CLIMB-AWARE BUDGET ADJUSTMENT (v3): EXTRAPOLATION + symmetric (extension OR shrink).
        // User: "zawsze dorzucaj gdy zostanie [wzniosu], nawet na czyms co uwazasz ze plaskie.
        // tak samo ucinaj km jesli jest duzo gor". Plus pre-GROW pomiar climb był MAŁY (trasa ~400 km
        // przed expansion), MIN threshold (20%) blokował extension dla deep deficits → 73% climb
        // realizacji zamiast 90%.
        //
        // Reguły v3:
        //  - Mierzymy aktualny climb-per-km, EKSTRAPOLUJEMY na full budget km (predict)
        //  - Predicted climb / climbBudget = predictedRatio
        //  - predictedRatio < 0.85 → extension: dorzuć km (cap +30%)
        //  - predictedRatio > 1.15 → shrink: ucinaj km (cap -20%)
        //  - 0.85 ≤ predictedRatio ≤ 1.15 → OK, bez zmian
        double effectiveBudgetKm = budgetKm;
        if (elevationGainFn != null && prefs.elevationPerDayM() != null && prefs.days() != null
                && prefs.elevationPerDayM() > 0 && prefs.days() > 0) {
            int climbBudgetM = prefs.days() * prefs.elevationPerDayM();
            try {
                int currentClimbM = elevationGainFn.apply(finalCalc.coordinates());
                double currentKm = Math.max(1, finalCalc.distanceKm());
                double climbPerKm = currentClimbM / currentKm;
                double predictedClimbAtBudget = climbPerKm * budgetKm;
                double predictedRatio = predictedClimbAtBudget / climbBudgetM;
                boolean isRoad = profile != null && profile.toLowerCase().contains("fastbike");
                double kmPerM = isRoad ? ROAD_KM_PER_M_CLIMB : OFFROAD_KM_PER_M_CLIMB;

                if (predictedRatio < CLIMB_DEFICIT_TRIGGER) {
                    // EXTENSION: dorzuć km żeby zwiększyć climb
                    double deficitM = climbBudgetM - predictedClimbAtBudget;
                    double rawExtension = deficitM * kmPerM;
                    double capUp = budgetKm * CLIMB_EXTENSION_MAX_RATIO;
                    double extensionKm = Math.min(rawExtension, capUp);
                    effectiveBudgetKm = budgetKm + extensionKm;
                    log.info("TSP climb-deficit extension (predicted): {}m/km × {}km = predict {}m / budget {}m ({}%), deficit {}m × {} km/m = +{} km (cap +{}), effBudget {} → {}",
                            new Object[]{String.format("%.2f", climbPerKm), budgetKm,
                                    Math.round(predictedClimbAtBudget), climbBudgetM,
                                    Math.round(predictedRatio * 100), Math.round(deficitM),
                                    String.format("%.3f", kmPerM),
                                    Math.round(extensionKm), Math.round(capUp),
                                    budgetKm, Math.round(effectiveBudgetKm)});
                } else if (predictedRatio > CLIMB_OVERSHOOT_TRIGGER) {
                    // SHRINK: utnij km żeby zmniejszyć climb (region zbyt górzysty)
                    double overshootM = predictedClimbAtBudget - climbBudgetM;
                    double rawShrink = overshootM * kmPerM;
                    double capDown = budgetKm * CLIMB_SHRINK_MAX_RATIO;
                    double shrinkKm = Math.min(rawShrink, capDown);
                    effectiveBudgetKm = budgetKm - shrinkKm;
                    log.info("TSP climb-overshoot shrink (predicted): {}m/km × {}km = predict {}m / budget {}m ({}%), overshoot {}m × {} km/m = -{} km (cap -{}), effBudget {} → {}",
                            new Object[]{String.format("%.2f", climbPerKm), budgetKm,
                                    Math.round(predictedClimbAtBudget), climbBudgetM,
                                    Math.round(predictedRatio * 100), Math.round(overshootM),
                                    String.format("%.3f", kmPerM),
                                    Math.round(shrinkKm), Math.round(capDown),
                                    budgetKm, Math.round(effectiveBudgetKm)});
                } else {
                    log.info("TSP climb OK (predicted): {}m/km × {}km = predict {}m / budget {}m ({}%) -- bez zmian",
                            new Object[]{String.format("%.2f", climbPerKm), budgetKm,
                                    Math.round(predictedClimbAtBudget), climbBudgetM,
                                    Math.round(predictedRatio * 100)});
                }
            } catch (RuntimeException e) {
                log.warn("TSP climb sampling failed: {} -- skip adjustment", e.getMessage());
            }
        }

        // GROW: jesli budzet niewykorzystany po dedup, dorzuc cheapest insertion z reserve.
        // User: "mechanizm dobierania jak budzetu jeszcze starcza". Threshold 0.99 -- nawet
        // 1% niedobor jest do uzupelnienia, czemuby nie wykorzystac calego budzetu.
        double afterDedupKm = finalCalc.distanceKm();
        if (afterDedupKm < effectiveBudgetKm * 0.99) {
            log.info("TSP GROW: realKm={} << effectiveBudget={} (km={}, climb-ext={}), dorzucam cheapest insertion z reserve",
                    new Object[]{Math.round(afterDedupKm), Math.round(effectiveBudgetKm),
                            budgetKm, Math.round(effectiveBudgetKm - budgetKm)});
            // Reset grid pickedAreaIds dla obszarow ktore NIE sa w aktualnym picked (dedup usunal entry-point ale picked zostaje)
            // Hmm, grid juz ma wszystkie picked oznaczone. Reserve = wszystko niepick + niepicked w grid.
            // Bezpieczniej: build nowy grid z aktualnym pickedSet.
            SpatialAreaIndex regrowGrid = new SpatialAreaIndex(GRID_CELL_DEG);
            java.util.Set<Integer> pickedIds = picked.stream()
                    .map(c -> c.getArea().areaId()).collect(java.util.stream.Collectors.toSet());
            // Iter 8: USUNIĘTO `isAreaNaturallyCovered` filter — był za agresywny: wycinał wszystkie
            // bliskie obszary (trasa zahacza o ring → "naturally covered"), zostawiajac TYLKO far-from-baseline
            // expensive w grid. GROW shell 10/20/30 km dawał 0 inserts bo grid był pusty w tych shell radius.
            // Teraz: wszystkie unpicked w grid. Cheapest insertion dorzuca po obu stronach baseline w shellach.
            // Dodanie wp w obszarze "naturalnie pokrytym" = mini-detour 1-3 km (entry-point 150m za granicą
            // ringa) -- trasa robi mini-zygzak BLISKO baseline, dokładnie to co user wskazał strzałkami.
            for (AreaCandidate c : allCandidates) {
                if (pickedIds.contains(c.getArea().areaId())) continue;
                regrowGrid.addAll(java.util.List.of(c));
            }
            // SHELL EXPANSION (Iteracja 8): zaczynamy od bufora 10 km od baseline, rozszerzamy
            // o 10 km dopóki budget nie wykorzystany. Max 100 km. User: "rozszerzal promien po
            // prostu, albo zmniejszal jak nie styknie budzetu".
            int growInserts = 0;
            int growFromAffordable = 0;
            int growFromExpensive = 0;
            double currentRealKm = afterDedupKm;
            double shellKm = SHELL_START_KM;
            // Iter 9 (round 2): INTERMIXED GROW↔dedup PER SHELL. Po każdym shell expansion robimy
            // dedup (eliminacja duplikatów odwiedzin), żeby zwolnić "miejsce" w budgecie i pozwolić
            // następnemu shell dorzucić więcej. User: "dalej zaliczac, znowu oczyscic duplikaty,
            // tak az osiagnie wynik bliski wykorzystania budżetu".
            final double GROW_TARGET_RATIO = 0.99;     // Iter 9 Fix #3: 0.97 → 0.99 (lepsze wykorzystanie budget)
            final double GROW_NO_PROGRESS_KM = 30.0;   // stop gdy 2 shell nie dorzuciły > 30 km
            double prevShellEndKm = currentRealKm;
            int noProgressCount = 0;
            while (shellKm <= SHELL_MAX_KM
                    && currentRealKm < effectiveBudgetKm * GROW_TARGET_RATIO) {
                int shellInserts = 0;
                int shellAffordable = 0;
                int shellExpensive = 0;
                // Phase A: shell expansion cheapest insertion
                while (currentRealKm < effectiveBudgetKm) {
                    BestInsertion best = findBestInsertionWithBuffer(tour, regrowGrid, roadAreas,
                            effectiveBudgetKm - currentRealKm, GROW_EDGE_BUFFER_KM, shellKm);
                    if (best == null) break;
                    tour.add(best.edgeIdx + 1,
                            new Waypoint(best.candidate.getEntryLng(), best.candidate.getEntryLat(),
                                    best.candidate.getArea().name()));
                    picked.add(best.candidate);
                    regrowGrid.markPicked(best.candidate);
                    currentRealKm += best.realDeltaKm;
                    growInserts++;
                    shellInserts++;
                    if (best.candidate.wasAffordable()) { growFromAffordable++; shellAffordable++; }
                    else { growFromExpensive++; shellExpensive++; }
                    if (growInserts % 15 == 0) {
                        try {
                            RouteCalculation growCalc = brouter.apply(tour, profile);
                            brouterCalls++;
                            currentRealKm = growCalc.distanceKm();
                            finalCalc = growCalc;
                        } catch (RuntimeException ignored) {}
                    }
                }
                // Phase B: re-BRouter na pełnym tour po shell expansion (jeśli były inserts)
                if (shellInserts > 0) {
                    try {
                        finalCalc = brouter.apply(tour, profile);
                        brouterCalls++;
                        currentRealKm = finalCalc.distanceKm();
                    } catch (RuntimeException ignored) {}
                }
                log.info("TSP GROW shell radius={} km: +{} inserts (drained={}, expensive={}), realKm={} / effBudget={} ({}%)",
                        new Object[]{Math.round(shellKm), shellInserts, shellAffordable, shellExpensive,
                                Math.round(currentRealKm), Math.round(effectiveBudgetKm),
                                Math.round(currentRealKm * 100.0 / effectiveBudgetKm)});
                // Phase C: dedup po tym shell. Zwolnij budget z duplicate visits.
                int beforeDedupWp = tour.size();
                finalCalc = velomarker.service.planning.AlnsCoveragePlanner.removeDuplicateVisitWaypoints(
                        picked, tour, finalCalc, profile, brouter, anchorNames);
                int dedupDropped = beforeDedupWp - tour.size();
                if (dedupDropped > 0) {
                    currentRealKm = finalCalc.distanceKm();
                    brouterCalls++; // dedup wewnątrz robi BRouter calls
                }
                // Progress check: jeśli ten shell + dedup nie dał > GROW_NO_PROGRESS_KM przyrostu, eskaluj
                double progress = currentRealKm - prevShellEndKm;
                if (progress < GROW_NO_PROGRESS_KM) {
                    noProgressCount++;
                    if (noProgressCount >= 2) {
                        log.info("TSP GROW: brak progresu (2× shell <30km przyrostu), break");
                        break;
                    }
                } else {
                    noProgressCount = 0;
                }
                prevShellEndKm = currentRealKm;
                shellKm += SHELL_STEP_KM;
            }
            if (currentRealKm < effectiveBudgetKm * 0.92) {
                log.info("TSP GROW: shell exhausted ({}km cap) -- akceptuje SHORT route realKm={} (target {})",
                        new Object[]{Math.round(SHELL_MAX_KM), Math.round(currentRealKm),
                                Math.round(effectiveBudgetKm)});
            }
            if (growInserts > 0) {
                // Iter 8: 2-opt + or-opt na pełnym tour PO GROW. Wcześniej 2-opt uruchamiał
                // się TYLKO przed GROW (55 wp), po dorzuceniu 261 wstawek nie było optymalizacji
                // order -> zygzaki/pętle. User: "w chuj przejazdów bez sensu".
                int swaps2optAfterGrow = apply2Opt(tour, anchorNames);
                int swapsOrOpt = applyOrOpt(tour, anchorNames);
                if (swaps2optAfterGrow > 0 || swapsOrOpt > 0) {
                    log.info("TSP post-GROW reorder: 2-opt={} swaps, or-opt={} moves", swaps2optAfterGrow, swapsOrOpt);
                }
                try {
                    finalCalc = brouter.apply(tour, profile);
                    brouterCalls++;
                    log.info("TSP GROW done: +{} obszarow (drained_affordable={}, expensive={}), realKm {} -> {} ({}% effectiveBudget={}, {}% kmBudget={})",
                            new Object[]{growInserts, growFromAffordable, growFromExpensive,
                                    Math.round(afterDedupKm), Math.round(finalCalc.distanceKm()),
                                    Math.round(finalCalc.distanceKm() * 100.0 / effectiveBudgetKm),
                                    Math.round(effectiveBudgetKm),
                                    Math.round(finalCalc.distanceKm() * 100.0 / budgetKm), budgetKm});
                } catch (RuntimeException e) {
                    log.warn("TSP GROW final BRouter failed: {}", e.getMessage());
                }
            }
        }

        // Iter 9: POST-BRouter DUPLICATE-VISIT DEDUP. User: "po kazdej iteracji brac to co
        // wyznaczyl brouter i wylapywac duplikaty". Wywołujemy WSPÓLNY helper z AlnsCoveragePlanner.
        // Eliminuje obszary które trasa odwiedza 2× z różnych stron (bezsensowne przebiegi).
        finalCalc = velomarker.service.planning.AlnsCoveragePlanner.removeDuplicateVisitWaypoints(
                picked, tour, finalCalc, profile, brouter, anchorNames);

        // Iter 9 opcja A: CLIMB POST-CHECK LOOP. Ekstrapolacja pre-GROW była niedokładna dla
        // trasy ZMIENNEJ (góry pośrodku, niziny na końcu). Tutaj iteracyjnie mierzymy REALNY
        // climb po pełnym GROW. Jeśli wciąż deficit > 15%, dorzucamy kolejny shell z większego
        // promienia + zwiększamy effectiveBudget. Max 3 iter.
        if (elevationGainFn != null && prefs.elevationPerDayM() != null && prefs.days() != null
                && prefs.elevationPerDayM() > 0 && prefs.days() > 0) {
            int climbBudgetM = prefs.days() * prefs.elevationPerDayM();
            boolean isRoad = profile != null && profile.toLowerCase().contains("fastbike");
            double kmPerM = isRoad ? ROAD_KM_PER_M_CLIMB : OFFROAD_KM_PER_M_CLIMB;
            final int MAX_CLIMB_POSTCHECK = 3;
            final double POSTCHECK_TARGET = 0.85;
            // ABSOLUTE CAP effBudget: 1.05 × budgetKm (user: cap 5%).
            final double MAX_EFFECTIVE_BUDGET = budgetKm * 1.05;
            // Per-iter progress check: jeśli climb wzrasta mniej niż MIN_CLIMB_GAIN_PER_ITER między
            // iteracjami → region NIE MA gór, koniec.
            final int MIN_CLIMB_GAIN_PER_ITER = 200;
            double postShellKm = SHELL_MAX_KM + SHELL_STEP_KM; // zaczynamy od dalszego shell niż główny GROW
            int prevClimbM = -1;
            for (int iter = 0; iter < MAX_CLIMB_POSTCHECK; iter++) {
                try {
                    int realClimbM = elevationGainFn.apply(finalCalc.coordinates());
                    double climbRatio = (double) realClimbM / climbBudgetM;
                    if (climbRatio >= POSTCHECK_TARGET) {
                        log.info("TSP climb post-check {}: realClimb={}m / budget={}m ({}%) {}",
                                new Object[]{iter == 0 ? "OK" : "iter " + iter + " CONVERGED",
                                        realClimbM, climbBudgetM, Math.round(climbRatio * 100),
                                        iter == 0 ? "" : "after " + iter + " iter"});
                        break;
                    }
                    // Per-iter progress check (od iter 1)
                    if (iter > 0 && prevClimbM > 0 && (realClimbM - prevClimbM) < MIN_CLIMB_GAIN_PER_ITER) {
                        log.info("TSP climb post-check iter {} STOP: climb gain {}m < {}m (region płaski, brak gór), realClimb={}m",
                                new Object[]{iter, realClimbM - prevClimbM, MIN_CLIMB_GAIN_PER_ITER, realClimbM});
                        break;
                    }
                    prevClimbM = realClimbM;
                    // ABSOLUTE CAP check
                    if (effectiveBudgetKm >= MAX_EFFECTIVE_BUDGET) {
                        log.info("TSP climb post-check iter {} STOP: effBudget {} hit hard cap {} (1.20× budget {})",
                                new Object[]{iter, Math.round(effectiveBudgetKm),
                                        Math.round(MAX_EFFECTIVE_BUDGET), budgetKm});
                        break;
                    }
                    // Deficit: zwiększ effectiveBudget proporcjonalnie + dodatkowy shell
                    int deficitM = climbBudgetM - realClimbM;
                    double extensionStep = Math.min(deficitM * kmPerM, budgetKm * 0.10); // max +10% per iter
                    // Respect absolute cap
                    extensionStep = Math.min(extensionStep, MAX_EFFECTIVE_BUDGET - effectiveBudgetKm);
                    if (extensionStep <= 0) break;
                    effectiveBudgetKm += extensionStep;
                    log.info("TSP climb post-check iter {}: realClimb={}m / budget={}m ({}%), deficit {}m → +{} km eff (→ {}, cap {}), shell {} km",
                            new Object[]{iter, realClimbM, climbBudgetM,
                                    Math.round(climbRatio * 100), deficitM,
                                    Math.round(extensionStep), Math.round(effectiveBudgetKm),
                                    Math.round(MAX_EFFECTIVE_BUDGET), Math.round(postShellKm)});
                    // Rebuild grid z aktualnym pickedSet i dorzuć jeszcze jeden shell
                    SpatialAreaIndex postGrid = new SpatialAreaIndex(GRID_CELL_DEG);
                    java.util.Set<Integer> pickedIdsNow = picked.stream()
                            .map(c -> c.getArea().areaId())
                            .collect(java.util.stream.Collectors.toSet());
                    for (AreaCandidate c : allCandidates) {
                        if (!pickedIdsNow.contains(c.getArea().areaId())) {
                            postGrid.addAll(java.util.List.of(c));
                        }
                    }
                    double currentKm = finalCalc.distanceKm();
                    int shellInserts = 0;
                    while (currentKm < effectiveBudgetKm) {
                        BestInsertion best = findBestInsertionWithBuffer(tour, postGrid, roadAreas,
                                effectiveBudgetKm - currentKm, GROW_EDGE_BUFFER_KM, postShellKm);
                        if (best == null) break;
                        tour.add(best.edgeIdx + 1,
                                new Waypoint(best.candidate.getEntryLng(), best.candidate.getEntryLat(),
                                        best.candidate.getArea().name()));
                        picked.add(best.candidate);
                        postGrid.markPicked(best.candidate);
                        currentKm += best.realDeltaKm;
                        shellInserts++;
                    }
                    if (shellInserts == 0) {
                        log.info("TSP climb post-check iter {}: brak kandydatów w shell {} km, koniec",
                                new Object[]{iter, Math.round(postShellKm)});
                        break;
                    }
                    finalCalc = brouter.apply(tour, profile);
                    brouterCalls++;
                    finalCalc = velomarker.service.planning.AlnsCoveragePlanner.removeDuplicateVisitWaypoints(
                            picked, tour, finalCalc, profile, brouter, anchorNames);
                    log.info("TSP climb post-check iter {} done: +{} wp, realKm {} (effBudget {})",
                            new Object[]{iter, shellInserts, Math.round(finalCalc.distanceKm()),
                                    Math.round(effectiveBudgetKm)});
                    postShellKm += SHELL_STEP_KM;
                } catch (RuntimeException e) {
                    log.warn("TSP climb post-check iter {} failed: {} -- break",
                            new Object[]{iter, e.getMessage()});
                    break;
                }
            }
        }

        // TRIM↔GROW KONWERGENCJA: po GROW realKm może być daleko od effectiveBudget (np. BRouter
        // zwrócił więcej niż straight-line estymata × roadAreas sugerowała). User: "jak jest grow
        // to powinien byc tez trim... az zblizymy sie dostatecznie dobrze do budzetu".
        //  - realKm > effBudget × 1.05  -> TRIM najdroższe wstawki
        //  - realKm < effBudget × 0.92  -> mini-GROW (cheapest insertion, max 30 inserts)
        //  - realKm w strefie [0.92, 1.05] -> done
        // Max 3 cykle, każdy = 1 BRouter call.
        for (int cycle = 0; cycle < MAX_TRIM_GROW_CYCLES; cycle++) {
            double realKm = finalCalc.distanceKm();
            double upperBound = effectiveBudgetKm * TRIM_TRIGGER;
            double lowerBound = effectiveBudgetKm * GROW_TRIGGER;
            if (realKm > upperBound) {
                int removed = trimToBudget(tour, picked, anchorNames, effectiveBudgetKm, realKm);
                if (removed == 0) {
                    log.info("TSP TRIM cycle {}: nothing to trim, breaking", cycle);
                    break;
                }
                try {
                    RouteCalculation trimmed = brouter.apply(tour, profile);
                    brouterCalls++;
                    log.info("TSP TRIM cycle {}: removed {} wp, realKm {} -> {} (target ~{})",
                            new Object[]{cycle, removed, Math.round(realKm),
                                    Math.round(trimmed.distanceKm()), Math.round(effectiveBudgetKm)});
                    finalCalc = trimmed;
                } catch (RuntimeException e) {
                    log.warn("TSP TRIM BRouter failed: {}", e.getMessage());
                    break;
                }
            } else if (realKm < lowerBound) {
                // Mini-GROW: dorzuc cheapest insertion z reserve.
                // Iter 8: BEZ `isAreaNaturallyCovered` filter (jak główny GROW).
                SpatialAreaIndex regrowGrid = new SpatialAreaIndex(GRID_CELL_DEG);
                java.util.Set<Integer> pickedIds = picked.stream()
                        .map(c -> c.getArea().areaId()).collect(java.util.stream.Collectors.toSet());
                for (AreaCandidate c : allCandidates) {
                    if (pickedIds.contains(c.getArea().areaId())) continue;
                    regrowGrid.addAll(java.util.List.of(c));
                }
                int miniGrowInserts = 0;
                double currentRealKm = realKm;
                final int MAX_MINI_GROW = 30;
                // Mini-GROW też shell expansion (Iteracja 8)
                double miniShellKm = SHELL_START_KM;
                while (miniShellKm <= SHELL_MAX_KM
                        && currentRealKm < effectiveBudgetKm
                        && miniGrowInserts < MAX_MINI_GROW) {
                    while (currentRealKm < effectiveBudgetKm && miniGrowInserts < MAX_MINI_GROW) {
                        BestInsertion best = findBestInsertionWithBuffer(tour, regrowGrid, roadAreas,
                                effectiveBudgetKm - currentRealKm, GROW_EDGE_BUFFER_KM, miniShellKm);
                        if (best == null) break;
                        tour.add(best.edgeIdx + 1,
                                new Waypoint(best.candidate.getEntryLng(), best.candidate.getEntryLat(),
                                        best.candidate.getArea().name()));
                        picked.add(best.candidate);
                        regrowGrid.markPicked(best.candidate);
                        currentRealKm += best.realDeltaKm;
                        miniGrowInserts++;
                    }
                    miniShellKm += SHELL_STEP_KM; // zawsze probuj kolejny shell
                }
                if (miniGrowInserts == 0) {
                    log.info("TSP mini-GROW cycle {}: no candidates found, breaking", cycle);
                    break;
                }
                try {
                    RouteCalculation grown = brouter.apply(tour, profile);
                    brouterCalls++;
                    log.info("TSP mini-GROW cycle {}: +{} wp, realKm {} -> {} (target ~{})",
                            new Object[]{cycle, miniGrowInserts, Math.round(realKm),
                                    Math.round(grown.distanceKm()), Math.round(effectiveBudgetKm)});
                    finalCalc = grown;
                } catch (RuntimeException e) {
                    log.warn("TSP mini-GROW BRouter failed: {}", e.getMessage());
                    break;
                }
            } else {
                log.info("TSP TRIM↔GROW: realKm={} w strefie [{}..{}], konwergencja po {} cyklach",
                        new Object[]{Math.round(realKm), Math.round(lowerBound),
                                Math.round(upperBound), cycle});
                break;
            }
        }

        // Fix 16: histogram dist-to-baseline per non-anchor picked waypoint.
        // Cele: zwalidować czy GROW dorzuca obszary daleko od korytarza. Bucket'y: 0-10, 10-30,
        // 30-50, 50-100, 100+ km. Affordable count z TOTAL picked dla source breakdown.
        java.util.Set<String> anchorNamesFinal = collectAnchorNames(prefs);
        int[] histBuckets = new int[5]; // 0-10, 10-30, 30-50, 50-100, 100+
        int totalAffordable = 0;
        int totalExpensive = 0;
        for (AreaCandidate c : picked) {
            if (c.isIntersected()) continue;
            if (anchorNamesFinal.contains(c.getArea().name())) continue;
            double d = c.getDistToBaselineKm();
            if (d < 10) histBuckets[0]++;
            else if (d < 30) histBuckets[1]++;
            else if (d < 50) histBuckets[2]++;
            else if (d < 100) histBuckets[3]++;
            else histBuckets[4]++;
            if (c.wasAffordable()) totalAffordable++; else totalExpensive++;
        }
        log.info("TSP waypoint distToBaseline histogram: 0-10km={}, 10-30km={}, 30-50km={}, 50-100km={}, 100+km={}",
                new Object[]{histBuckets[0], histBuckets[1], histBuckets[2], histBuckets[3], histBuckets[4]});
        log.info("TSP picked source: affordable={} expensive={} (z {} non-intersected picked)",
                new Object[]{totalAffordable, totalExpensive, totalAffordable + totalExpensive});

        long elapsedMs = System.currentTimeMillis() - startTs;
        log.info("TSP done: picked={} (incl {} free intersected) tour={} wp finalRealKm={} budget={} effBudget={} brouterCalls={} inserts={} rejectedByBudget={} elapsedMs={}",
                new Object[]{picked.size(),
                        picked.stream().filter(AreaCandidate::isIntersected).count(),
                        tour.size(), Math.round(finalCalc.distanceKm()), budgetKm,
                        Math.round(effectiveBudgetKm),
                        brouterCalls, inserts, rejectedByBudget, elapsedMs});

        // FINAL RECOMPUTE: wewnetrzne wywolania brouter.apply uzywaja computeStats=false. Tu jedno
        // wywolanie brouterFinal (computeStats=true) zeby zwracany RouteCalculation mial pelne stats.
        try {
            RouteCalculation recomputed = brouterFinal.apply(tour, profile);
            log.info("TSP final recompute z stats: distanceKm={} stats.totalMeters={}",
                    new Object[]{Math.round(recomputed.distanceKm()),
                            recomputed.stats() != null ? recomputed.stats().totalMeters() : 0});
            finalCalc = recomputed;
            brouterCalls++;
        } catch (RuntimeException ex) {
            log.warn("TSP final recompute z stats failed ({}) — zwracam wynik bez stats", ex.getMessage());
        }

        return new TspResult(finalCalc, tour, picked, inserts, brouterCalls, rejectedByBudget);
    }

    /**
     * TRIM: usuń non-anchor entry-points o najwyższym lokalnym detour cost, aż trasa zmieści
     * się w budget × 1.02 (z 5% marginesem zapasu). Detour cost per wp = haversine sum
     * (prev→wp→next) - haversine bezpośrednio (prev→next). Im wyższy -- tym bardziej "drogi"
     * wjazd. Sortujemy DESC, usuwamy od góry aż osiągniemy target.
     *
     * <p>Z proxy roadAreas factor ≈ 1.5 (poprawione przez fakt że trim w real BRouter da
     * dokładny km). Nie idealne, ale konwergencja w 2-3 cykli wystarcza.
     *
     * @return liczba usuniętych entry-points
     */
    private static int trimToBudget(List<Waypoint> tour, List<AreaCandidate> picked,
                                      java.util.Set<String> anchorNames,
                                      double targetKm, double currentKm) {
        double surplus = currentKm - targetKm;
        if (surplus <= 0) return 0;
        record CostIdx(int idx, double cost, String name) {}
        List<CostIdx> costs = new ArrayList<>();
        for (int i = 1; i < tour.size() - 1; i++) {
            Waypoint wp = tour.get(i);
            if (anchorNames.contains(wp.name())) continue;
            double[] prev = tour.get(i - 1).toLngLat();
            double[] cur = wp.toLngLat();
            double[] next = tour.get(i + 1).toLngLat();
            double cost = velomarker.service.planning.WaypointSelector.haversineKm(prev, cur)
                    + velomarker.service.planning.WaypointSelector.haversineKm(cur, next)
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
            costs.add(new CostIdx(i, cost, wp.name()));
        }
        costs.sort((a, b) -> Double.compare(b.cost(), a.cost())); // DESC by cost

        // Proxy: real km saved ≈ cost × 1.5 (roadAreas factor). Greedy collect aż surplus zniknie.
        double savedSoFar = 0;
        java.util.Set<Integer> toRemove = new java.util.HashSet<>();
        java.util.Set<String> namesToRemove = new java.util.HashSet<>();
        for (CostIdx e : costs) {
            if (savedSoFar >= surplus) break;
            toRemove.add(e.idx());
            namesToRemove.add(e.name());
            savedSoFar += e.cost() * 1.5;
        }
        if (toRemove.isEmpty()) return 0;

        // Remove from tour (reverse-index order)
        List<Integer> sorted = new ArrayList<>(toRemove);
        sorted.sort(java.util.Collections.reverseOrder());
        for (int idx : sorted) tour.remove(idx);

        // Remove from picked too (po nazwie — tylko non-intersected, intersected zostają jako free)
        picked.removeIf(c -> !c.isIntersected() && namesToRemove.contains(c.getArea().name()));

        return toRemove.size();
    }

    // ── Insertion search ───────────────────────────────────────────────────────────────────

    private record BestInsertion(AreaCandidate candidate, int edgeIdx, double realDeltaKm) {}

    /**
     * Iteruje po edges tour. Per edge: query grid o sąsiednie unpicked candidates, oblicza
     * insertion cost (delta straight × roadAreas). Track cheapest globally.
     *
     * @param remainingBudgetKm ile km zostalo do budzetu (rejected jesli przekracza)
     */
    private BestInsertion findBestInsertion(List<Waypoint> tour, SpatialAreaIndex grid,
                                              double roadAreas, double remainingBudgetKm) {
        return findBestInsertionWithBuffer(tour, grid, roadAreas, remainingBudgetKm, EDGE_BUFFER_KM, Double.MAX_VALUE);
    }

    private BestInsertion findBestInsertionWithBuffer(List<Waypoint> tour, SpatialAreaIndex grid,
                                                        double roadAreas, double remainingBudgetKm,
                                                        double bufferKm) {
        return findBestInsertionWithBuffer(tour, grid, roadAreas, remainingBudgetKm, bufferKm, Double.MAX_VALUE);
    }

    /**
     * Cheapest insertion z AREA-WEIGHTED bonus: duze obszary (DE Kreis 600 km²) sa preferowane
     * nad malymi (CZ Obec 30 km²) przy podobnym detour. Bonus = sqrt(areaKm²) × 0.5 km.
     *
     * <p>Iteracja 8 / Fix 17 SHELL EXPANSION: parametr `maxDistToBaselineKm` filtruje obszary
     * dalsze niż dany promień od baseline. GROW iteruje shells 10, 20, 30... km żeby najpierw
     * zaliczać blisko, dopiero gdy budżet nie wykorzystany rozszerzyć promień. User: "to nie
     * odjezdzaj raz ale promieniowo... rozszerzal promien po prostu, albo zmniejszal jak nie
     * styknie budzetu".
     */
    private BestInsertion findBestInsertionWithBuffer(List<Waypoint> tour, SpatialAreaIndex grid,
                                                        double roadAreas, double remainingBudgetKm,
                                                        double bufferKm,
                                                        double shellKmFromEdge) {
        // Iter 9: SHELL RADIUS mierzony OD AKTUALNEGO EDGE TOUR (NIE od baseline). User: "czemu
        // siega po coraz wiekszy shell skoro ma blizsze gminy". Aktualnie trasa rośnie, ale shell
        // filter był od BASELINE — obszary 5 km od trasy ale 50 km od baseline wpadały dopiero do
        // shell 50. Po fix: shell 10 = wszystkie 10 km od TRASY (= najbliższy edge segment).
        BestInsertion best = null;
        double bestEffectiveCost = Double.MAX_VALUE;
        // Query buffer = shellKmFromEdge × MAX_AREA_SCALE (=12, dla Provincia 5000 km²).
        // Plus zostaje min bufferKm (np. 50 km dla GROW) jako safety net.
        double queryBuffer = Math.max(bufferKm, shellKmFromEdge * 12.0);
        for (int i = 0; i < tour.size() - 1; i++) {
            double[] p1 = tour.get(i).toLngLat();
            double[] p2 = tour.get(i + 1).toLngLat();
            double existingEdgeKm = WaypointSelector.haversineKm(p1, p2);
            List<AreaCandidate> nearby = grid.queryAlongEdge(p1, p2, queryBuffer);
            for (AreaCandidate c : nearby) {
                if (c.isIntersected()) continue;
                // Per-area shell scaling (większe obszary tolerują szerszy shell)
                double areaScale = Math.max(1.0, Math.sqrt(Math.max(0, c.getArea().areaKm2())) / 6.0);
                double effectiveMaxDistFromEdge = shellKmFromEdge * areaScale;
                // Distance from area entry-point to current edge segment
                double dEdge = SpatialAreaIndex.distancePointToSegmentKm(
                        c.getEntryLng(), c.getEntryLat(), p1[0], p1[1], p2[0], p2[1]);
                if (dEdge > effectiveMaxDistFromEdge) continue;
                double[] cp = new double[]{c.getEntryLng(), c.getEntryLat()};
                double d1 = WaypointSelector.haversineKm(p1, cp);
                double d2 = WaypointSelector.haversineKm(cp, p2);
                double deltaStraight = d1 + d2 - existingEdgeKm;
                double realDelta = Math.max(0, deltaStraight * roadAreas);
                if (realDelta > remainingBudgetKm) continue;
                // Area-weighted bonus: duze obszary "tansze" w cheapest insertion ranking
                double areaBonus = Math.sqrt(Math.max(0, c.getArea().areaKm2())) * 0.5;
                // Iter 9: USUNIĘTO corridor penalty (mierzony od baseline). Shell radius już
                // wymusza bliskość do tour, nie potrzeba dodatkowego bonusu od linii start→end.
                double effectiveCost = realDelta - areaBonus;
                if (effectiveCost < bestEffectiveCost) {
                    bestEffectiveCost = effectiveCost;
                    best = new BestInsertion(c, i, realDelta);
                }
            }
        }
        return best;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    /**
     * 2-opt local search: dla par krawedzi (i, j) sprawdz czy reverse segmentu [i, j] daje
     * KROTSZA sume krawedzi. Jesli tak -- apply. Powtarzaj az brak improvement.
     *
     * <p>Anchors (start/via/end) NIE moga byc reversed -- pomin pary gdzie jakikolwiek z
     * indeksow {i-1, i, j, j+1} jest anchor. To zachowuje constraint A->via->B.
     *
     * @return liczba zastosowanych swapów (do logu diagnostyki)
     */
    static int apply2Opt(List<Waypoint> tour, java.util.Set<String> anchorNames) {
        int totalSwaps = 0;
        boolean improved = true;
        int iter = 0;
        final int MAX_ITER = 50;
        final double IMPROVE_EPSILON = 0.01; // 10m -- ignoruj negligible improvements
        while (improved && iter < MAX_ITER) {
            improved = false;
            iter++;
            for (int i = 1; i < tour.size() - 2; i++) {
                if (isAnchor(tour.get(i), anchorNames)) continue;
                double[] a = tour.get(i - 1).toLngLat();
                double[] b = tour.get(i).toLngLat();
                for (int j = i + 1; j < tour.size() - 1; j++) {
                    if (isAnchor(tour.get(j), anchorNames)) continue;
                    // Pomin jesli segment [i+1, j] zawiera anchor (np. via miedzy)
                    boolean segmentClean = true;
                    for (int k = i + 1; k < j; k++) {
                        if (isAnchor(tour.get(k), anchorNames)) { segmentClean = false; break; }
                    }
                    if (!segmentClean) continue;
                    double[] c = tour.get(j).toLngLat();
                    double[] d = tour.get(j + 1).toLngLat();
                    double oldCost = velomarker.service.planning.WaypointSelector.haversineKm(a, b)
                            + velomarker.service.planning.WaypointSelector.haversineKm(c, d);
                    double newCost = velomarker.service.planning.WaypointSelector.haversineKm(a, c)
                            + velomarker.service.planning.WaypointSelector.haversineKm(b, d);
                    if (newCost < oldCost - IMPROVE_EPSILON) {
                        // Reverse segment [i, j] inclusive
                        java.util.Collections.reverse(tour.subList(i, j + 1));
                        totalSwaps++;
                        improved = true;
                        break; // restart from beginning of new tour
                    }
                }
                if (improved) break;
            }
        }
        return totalSwaps;
    }

    /**
     * Or-opt local search (Iter 8): dla każdego non-anchor wp `i`, sprawdź czy przeniesienie
     * go w lepszą pozycję `j` skraca trasę. Or-opt eliminuje **zygzaki które 2-opt (reverse
     * segment) nie usuwa** -- np. wp wstawiony do złej edge przez cheapest insertion w GROW.
     *
     * <p>Cost change dla move wp z pozycji `i` do pozycji `j`:
     * <pre>
     *   removed = d(i-1, i) + d(i, i+1) - d(i-1, i+1)   (wstaw wp z back)
     *   added   = d(j, i_new) + d(i_new, j+1) - d(j, j+1)   (wstaw w nowe miejsce)
     *   delta   = added - removed   (negative = improvement)
     * </pre>
     *
     * @return liczba zastosowanych moves
     */
    static int applyOrOpt(List<Waypoint> tour, java.util.Set<String> anchorNames) {
        int totalMoves = 0;
        boolean improved = true;
        int iter = 0;
        final int MAX_ITER = 30;
        final double IMPROVE_EPSILON = 0.01;
        while (improved && iter < MAX_ITER) {
            improved = false;
            iter++;
            for (int i = 1; i < tour.size() - 1; i++) {
                if (isAnchor(tour.get(i), anchorNames)) continue;
                double[] prev = tour.get(i - 1).toLngLat();
                double[] cur = tour.get(i).toLngLat();
                double[] next = tour.get(i + 1).toLngLat();
                double removed = velomarker.service.planning.WaypointSelector.haversineKm(prev, cur)
                        + velomarker.service.planning.WaypointSelector.haversineKm(cur, next)
                        - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
                // Spróbuj każdą inną pozycję j (między anchorami)
                int bestJ = -1;
                double bestDelta = -IMPROVE_EPSILON;
                for (int j = 0; j < tour.size() - 1; j++) {
                    if (j == i - 1 || j == i) continue; // ta sama pozycja
                    double[] a = tour.get(j).toLngLat();
                    double[] b = tour.get(j + 1).toLngLat();
                    double added = velomarker.service.planning.WaypointSelector.haversineKm(a, cur)
                            + velomarker.service.planning.WaypointSelector.haversineKm(cur, b)
                            - velomarker.service.planning.WaypointSelector.haversineKm(a, b);
                    double delta = added - removed;
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestJ = j;
                    }
                }
                if (bestJ >= 0) {
                    // Move tour[i] to position bestJ+1 (after tour[bestJ])
                    Waypoint w = tour.remove(i);
                    int insertAt = bestJ < i ? bestJ + 1 : bestJ; // adjust po remove
                    tour.add(insertAt, w);
                    totalMoves++;
                    improved = true;
                    break; // restart
                }
            }
        }
        return totalMoves;
    }

    /**
     * Turf-like ring intersection: czy JAKIKOLWIEK punkt trasy jest wewnatrz ringu obszaru.
     * Bbox prefilter eliminuje >95% punktow (route ma ~30k punktow, ring bbox to ulamek mapy)
     * zanim platne pointInRing.
     *
     * <p>Zastapilo: isAreaNaturallyCovered z ±300 indeksami wokol centroidu, ktore traci trasy
     * wchodzace w ring spoza okolicy centroidu (np. ring wydluzony, trasa wchodzi w "rog").
     */
    static boolean isRingTouchedByRoute(AreaCandidate c, List<double[]> geometry) {
        if (geometry == null || geometry.isEmpty()) return false;
        double[][] ring = c.getArea().ring();
        if (ring == null || ring.length < 3) return false;
        double minLng = ring[0][0], maxLng = minLng, minLat = ring[0][1], maxLat = minLat;
        for (double[] p : ring) {
            if (p[0] < minLng) minLng = p[0];
            else if (p[0] > maxLng) maxLng = p[0];
            if (p[1] < minLat) minLat = p[1];
            else if (p[1] > maxLat) maxLat = p[1];
        }
        for (double[] g : geometry) {
            if (g[0] < minLng || g[0] > maxLng || g[1] < minLat || g[1] > maxLat) continue;
            if (velomarker.service.planning.WaypointSelector.pointInRing(g, ring)) return true;
        }
        return false;
    }

    /** Czy area's ring jest przeciety przez geometrie (sprawdz +-300 punktow wokol najblizszego do centroidu). */
    static boolean isAreaNaturallyCovered(AreaCandidate c, List<double[]> geometry) {
        if (geometry == null || geometry.isEmpty()) return false;
        double[][] ring = c.getArea().ring();
        if (ring == null || ring.length < 3) return false;
        // Najblizszy punkt geometrii do centroidu area
        double[] center = new double[]{c.getArea().lng(), c.getArea().lat()};
        int nearIdx = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < geometry.size(); i++) {
            double d = velomarker.service.planning.WaypointSelector.haversineKm(geometry.get(i), center);
            if (d < minDist) { minDist = d; nearIdx = i; }
        }
        // Sprawdz ±300 indices wokol -- ring touched gdy jakikolwiek punkt geometrii w ringu
        int from = Math.max(0, nearIdx - 300);
        int to = Math.min(geometry.size(), nearIdx + 300);
        for (int i = from; i < to; i++) {
            if (velomarker.service.planning.WaypointSelector.pointInRing(geometry.get(i), ring)) {
                return true;
            }
        }
        return false;
    }

    /** Anchor names: start, via*, end. */
    static java.util.Set<String> collectAnchorNames(RoutePreferences prefs) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (prefs.start() != null && prefs.start().name() != null) names.add(prefs.start().name());
        if (prefs.end() != null && prefs.end().name() != null) names.add(prefs.end().name());
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) if (v.name() != null) names.add(v.name());
        }
        return names;
    }

    static boolean isAnchor(Waypoint wp, java.util.Set<String> anchorNames) {
        return wp.name() != null && anchorNames.contains(wp.name());
    }

    private static List<Waypoint> buildAnchorTour(RoutePreferences prefs) {
        List<Waypoint> tour = new ArrayList<>();
        tour.add(prefs.start());
        if (prefs.via() != null) tour.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) {
            tour.add(prefs.start());
        } else if (prefs.end() != null) {
            tour.add(prefs.end());
        } else {
            tour.add(prefs.start());
        }
        return tour;
    }
}
