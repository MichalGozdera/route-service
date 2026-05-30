package velomarker.service.planning.alns2;

import async.TaskCancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.AreaCoverageIndexFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * ALNS2: Orienteering / Max Coverage Path Solver.
 *
 * <p>Reformulacja problemu: ZAMIAST minimalizować tour length (TSP cheapest insertion),
 * MAKSYMALIZUJEMY coverage (visited gminy) przy hard budget effort. Klasyczny Orienteering
 * Problem z destroy/repair operatorów + Simulated Annealing.
 *
 * <h3>Effort model</h3>
 * <pre>
 *   ALPHA = 0.1 km/m
 *   DAILY_LIMIT = kmPerDay + ALPHA * elevationPerDayM
 *   TOTAL_LIMIT = DAILY_LIMIT * days
 * </pre>
 * Profile-independent — 1m wzniosu zawsze = 0.1 km efortu.
 *
 * <h3>Score function</h3>
 * <pre>
 *   score(route) = Σ reward(g) for g in visited(route)
 *                  − BETA × dziury_blisko_trasy(R_NEAR)
 * </pre>
 *
 * <h3>Iteracja</h3>
 * <pre>
 *   route = shortest_path(start, end)
 *   for iter in 1..MAX_ITERS:
 *     R = destroy(route)
 *     R = repair(R)              ← najbliższe → najlepsze score/effort
 *     R = local_search(R)        ← 2-opt + relocate + swap
 *     if effort(R) > TOTAL: continue
 *     accept(R, route, T) (SA)
 *     update best
 * </pre>
 */
public class AlnsCoveragePlanner2 {

    private static final Logger log = LoggerFactory.getLogger(AlnsCoveragePlanner2.class);

    private final Alns2Parameters params;
    private final ElevationDataSource elevation;
    private final Random rand;
    /** false = ALNS2 (projekcja 1D na baseline, dla cienkiego korytarza). true = ALNS3 (serpentyna:
     * paski wzdłuż baseline + wężyk w poprzek, dla grubego pasa / short-baseline-big-budget). */
    private final boolean serpentine;
    /** Szerokość paska serpentyny w km (tylko gdy serpentine=true). */
    private final double stripKm;
    /** Ile krawędzi BRouter liczyć RÓWNOLEGLE (pre-warm). = route.calculate.max-concurrent. */
    private final int brouterParallelism;
    /** true = zwróć od razu wynik seeda (grow+prune+2opt+trim), POMIŃ pętlę SA. Do porównania
     * „seed vs iteracje" + oszczędności czasu BRoutera. application.yml: planning.alns2.seed-only. */
    private final boolean seedOnly;
    /** true = proxy-search: w trakcie seedu/SA effort liczony z haversine × {@link RegionFactorGrid}
     * (leniwa kalibracja per-region), zamiast realnego BRoutera per krawędź. Finalny realny chunked
     * BRouter (po proxy-search) liczy autorytatywny wynik. application.yml: planning.alns2.proxy-search. */
    private final boolean proxySearch;
    private final double proxyCellDeg;
    private final int proxyRecalibrateEvery;
    /** Grid kalibracji proxy — ustawiany per plan() gdy proxySearch. null = tryb realny per-edge. */
    private RegionFactorGrid proxyGrid;
    /** Bbox centroidów kandydatów (ustawiany per plan()) — do Hilbert space-filling (alns3). */
    private double bMinLng, bMinLat, bMaxLng, bMaxLat;
    /** Krawędzie, dla których BRouter rzucił (target-island / nieosiągalne). Sygnał „wyspa" — seed
     * prune usuwa takie waypointy zanim trafią do finalnego chunked BRoutera (który by się wywalił).
     * Thread-safe (równoległy pre-warm). Czyszczone per plan(). */
    private final java.util.Set<String> failedEdges = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Fabryka JTS coverage indexu (PEŁNA geometria, plain intersect) — budowana per plan() nad pulą. */
    private final AreaCoverageIndexFactory coverageFactory;
    /** Reconcile-swap: gdy brak budżetu na łatanie dziury, utnij najdroższą „mackę" (daleki waypoint
     *  z dużym detourem) i wstaw w to miejsce bliską dziurę (netto w budżecie). application.yml:
     *  planning.alns2.reconcile-swap. Wyłączony → reconcile tylko rośnie do 100% i staje. */
    private final boolean reconcileSwap;

    /** {@code serpentine=true} = ALNS3 space-filling (HILBERT); {@code proxySearch} = proxy-effort. */
    public AlnsCoveragePlanner2(Alns2Parameters params, ElevationDataSource elevation,
                                boolean serpentine, double stripKm, int brouterParallelism,
                                boolean seedOnly, boolean proxySearch, double proxyCellDeg,
                                int proxyRecalibrateEvery, boolean reconcileSwap,
                                AreaCoverageIndexFactory coverageFactory) {
        this.params = params;
        this.elevation = elevation;
        this.rand = new Random(42);
        this.serpentine = serpentine;
        this.stripKm = stripKm;
        this.brouterParallelism = Math.max(1, brouterParallelism);
        this.seedOnly = seedOnly;
        this.proxySearch = proxySearch;
        this.proxyCellDeg = proxyCellDeg > 0 ? proxyCellDeg : 0.5;
        this.proxyRecalibrateEvery = Math.max(1, proxyRecalibrateEvery);
        this.reconcileSwap = reconcileSwap;
        this.coverageFactory = coverageFactory;
    }

    /** Result struktura (analogiczna do TspResult / AlnsResult). */
    public record Alns2Result(
            RouteCalculation calc,
            List<Waypoint> finalWaypoints,
            List<UnvisitedArea> visited,
            int iterations,
            int brouterCalls,
            int accepted,
            int rejected
    ) {}

    public Alns2Result plan(UUID taskId,
                             List<UnvisitedArea> candidatePool,
                             RoutePreferences prefs,
                             String profile,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                             Consumer<UUID> checkCancel) {
        long startTs = System.currentTimeMillis();
        long maxMs = params.maxTimeSeconds() * 1000L;

        // Effort budget
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : 200;
        int elevPerDay = prefs.elevationPerDayM() != null ? prefs.elevationPerDayM() : 0;
        int days = prefs.days() != null ? prefs.days() : 1;
        double dailyLimit = kmPerDay + params.alphaKmPerMeter() * elevPerDay;
        double totalLimit = dailyLimit * days;

        log.info("ALNS2 init: budget effort={} (= {} km + {} × {} m × {} days), candidates={}",
                new Object[]{Math.round(totalLimit), kmPerDay,
                        params.alphaKmPerMeter(), elevPerDay, days, candidatePool.size()});

        // Index + cache. Coverage (zaliczenia) liczy JTS na PEŁNEJ geometrii (plain intersect jak front).
        GminaIndex gminaIndex = new GminaIndex(candidatePool, params.samplePointsPerGmina(),
                coverageFactory.build(candidatePool));
        EdgeCache cache = new EdgeCache();
        failedEdges.clear();
        // Proxy-search: grid kalibracji per plan. Leniwa kalibracja (1. krawędź w regionie = realny
        // probe, reszta proxy). null = tryb realny per-edge (jak dotąd).
        proxyGrid = proxySearch ? new RegionFactorGrid(proxyCellDeg, proxyRecalibrateEvery) : null;
        if (proxySearch) {
            log.info("ALNS2 PROXY-SEARCH: effort z haversine×grid(cell={}°, recalib={}), real BRouter tylko probe+finał",
                    new Object[]{proxyCellDeg, proxyRecalibrateEvery});
        }
        // Bbox kandydatów — dla Hilbert space-filling (alns3). Liczony raz.
        computeCandidateBbox(candidatePool);
        // Corridor penalty OFF w trybie space-filling (Hilbert MA się rozlewać równo, nie tulić do baseline).
        double effCorridor = serpentine ? 0.0 : params.corridorFactor();

        // Reward per kategoria (Iter 11 Fix 2: NN-distance proportion, logged inside)
        Map<String, Double> rewardPerCategory = computeRewardPerCategory(candidatePool);
        // areaId → human kategoria (do per-iter coverage breakdown w logach)
        Map<Integer, String> areaCat = new HashMap<>();
        for (UnvisitedArea a : candidatePool) {
            areaCat.put(a.areaId(), formatCategoryKey(rewardCategoryKey(a)));
        }

        // INITIAL ROUTE = shortest path (start, [via...], end)
        List<Waypoint> initialWps = new ArrayList<>();
        initialWps.add(prefs.start());
        if (prefs.via() != null) initialWps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) initialWps.add(prefs.start());
        else if (prefs.end() != null) initialWps.add(prefs.end());
        else initialWps.add(prefs.start());

        RouteCalculation initialCalc = brouter.apply(initialWps, profile);
        int brouterCalls = 1;
        // Iter 11 Fix 1: baseline geometry (shortest path start→via→end) — corridor anchor.
        // Punkty trasy daleko od tej linii płacą karę (repair ranking + seed + score).
        List<double[]> baseline = downsample(initialCalc.coordinates(), 200);
        // Anchors (start + via + end) — NIE rusza ich destroy
        List<double[]> anchors = new ArrayList<>();
        for (Waypoint w : initialWps) anchors.add(w.toLngLat());
        // Route = anchors only (start → via → end) — początkowo wąska
        List<double[]> route = new ArrayList<>(anchors);

        // GREEDY SEED: saturate trasy do 70% TOTAL_LIMIT przed SA. User: shortest_path daje
        // zbyt krótki seed (80 km) dla budgetu 3000 km. Bez seed SA nigdy nie urośnie do
        // budgetu bo destroy/repair tylko oscyluje w pasie R_NEAR od shortest_path.
        // Seed rośnie po REALNYM efforcie (edge cache) aż do 0.93×budżet — wypełnia budżet niezależnie
        // od terenu (płaski region → więcej km, mało wzniosu, ten sam effort). Proxy zostawiał ~900 km.
        // User: seed ma lądować NIŻEJ (90-95%), zostawiając zapas, a densify dopina donut-holes
        // do ~100% (rezerwa 10% dla reconcile). Inaczej greedy zjadał budżet dalekimi mackami i densify nie miał
        // z czego łatać dziur przy trasie. targetEffort=budżet (referencja dla pasm/sufitu w seedzie).
        double seedTarget = totalLimit;
        log.info("ALNS2 greedy seed: budget effort={} (seed→90-95%, densify→100%, rezerwa 10% dla reconcile→110%)",
                new Object[]{Math.round(seedTarget)});
        greedySeedRoute(route, anchors, gminaIndex, candidatePool, rewardPerCategory,
                seedTarget, params.alphaKmPerMeter(), baseline, effCorridor,
                cache, brouter, profile);

        // 2-opt + relocate na seed (haversine proxy) — bez tego seed zostaje bestem z zygzakami.
        Alns2LocalSearch.twoOpt(route);
        Alns2LocalSearch.relocate(route);

        // EVAL przez EdgeCache (per-edge, NIE pełny chunked BRouter). Pierwsza ewaluacja populuje
        // cache wszystkich ~N krawędzi; kolejne iteracje SA liczą tylko ZMIENIONE krawędzie.
        EvalResult seedEval = evalRoute(route, cache, brouter, profile, params.alphaKmPerMeter(), gminaIndex);
        brouterCalls += (int) cache.misses();

        double currentEffort = seedEval.effort();
        Set<Integer> currentVisited = seedEval.visited();
        List<double[]> currentGeom = seedEval.geometry();
        double currentScore = scoreDetailed(currentVisited, route, currentGeom,
                candidatePool, gminaIndex, rewardPerCategory, baseline, params.rNearKm(),
                params.beta(), params.gamma(), params.delta(), effCorridor).total();

        List<double[]> bestRoute = new ArrayList<>(route);
        double bestScore = currentScore;
        double bestEffort = currentEffort;
        Set<Integer> bestVisitedIds = currentVisited;
        List<Waypoint> bestWps = buildWaypoints(route, prefs);

        log.info("ALNS2 after seed (+2opt): route_size={} effort={}/{} ({}%) visited={} score={}",
                new Object[]{route.size(), Math.round(currentEffort), Math.round(totalLimit),
                        Math.round(currentEffort * 100.0 / totalLimit),
                        currentVisited.size(), String.format("%.1f", currentScore)});

        double T = params.tStart();
        int accepted = 0;
        int rejected = 0;
        int noImprove = 0;
        // Adaptive R_NEAR: gdy effort daleko od limit, ekspansuj rNear żeby repair znalazł więcej
        double adaptiveRNear = params.rNearKm();

        // === MAIN SA LOOP === (seedOnly → maxIters=0 = pomiń, zwróć czysty wynik seeda do porównania)
        int maxIters = seedOnly ? 0 : params.maxIters();
        if (seedOnly) log.info("ALNS2 SEED-ONLY mode: pomijam SA, zwracam wynik seeda (porównanie / oszczędność BRoutera)");
        int iter = 0;
        for (iter = 0; iter < maxIters; iter++) {
            if (System.currentTimeMillis() - startTs > maxMs) {
                log.warn("ALNS2 hit max-time-seconds ({}) at iter={}", params.maxTimeSeconds(), iter);
                break;
            }
            try { checkCancel.accept(taskId); }
            catch (TaskCancellationException tce) {
                log.info("ALNS2 cancelled at iter={}", iter);
                throw tce;
            }

            // DESTROY
            List<double[]> candidate = new ArrayList<>(route);
            destroy(candidate, anchors, params.destroyRatio(), gminaIndex, currentVisited);

            // REPAIR: greedy insertion z reward/delta ranking (real BRouter z EdgeCache)
            repair(candidate, anchors, gminaIndex, candidatePool, rewardPerCategory,
                    adaptiveRNear, totalLimit, params.alphaKmPerMeter(),
                    baseline, effCorridor,
                    cache, brouter, profile);

            // LOCAL SEARCH (haversine proxy)
            Alns2LocalSearch.twoOpt(candidate);
            Alns2LocalSearch.relocate(candidate);

            // PRE-SCREEN: proxy effort z haversine. Jeśli proxy MOCNO over budget, skip eval.
            double proxyEffort = approxEffort(candidate, params.alphaKmPerMeter());
            if (proxyEffort * 1.3 > totalLimit) {
                rejected++;
                continue;
            }

            // EVAL przez EdgeCache — tylko ZMIENIONE krawędzie (destroy/repair) to cache miss,
            // reszta trasy = cache hit. Brak pełnego chunked BRouter per iter (był ~30-40s).
            long missesBefore = cache.misses();
            EvalResult candEval = evalRoute(candidate, cache, brouter, profile,
                    params.alphaKmPerMeter(), gminaIndex);
            brouterCalls += (int) (cache.misses() - missesBefore);
            double candidateEffort = candEval.effort();
            if (candidateEffort > totalLimit) {
                rejected++;
                continue;
            }

            Set<Integer> candidateVisited = candEval.visited();
            ScoreBreakdown sb = scoreDetailed(candidateVisited, candidate, candEval.geometry(),
                    candidatePool, gminaIndex, rewardPerCategory, baseline, params.rNearKm(),
                    params.beta(), params.gamma(), params.delta(), effCorridor);
            double candidateScore = sb.total();

            boolean isBest = candidateScore > bestScore;
            if (isBest) {
                bestRoute = new ArrayList<>(candidate);
                bestScore = candidateScore;
                bestEffort = candidateEffort;
                bestVisitedIds = candidateVisited;
                bestWps = buildWaypoints(candidate, prefs);
                noImprove = 0;
            } else {
                noImprove++;
            }

            if (acceptSA(candidateScore, currentScore, T)) {
                route = candidate;
                currentScore = candidateScore;
                currentEffort = candidateEffort;
                currentVisited = candidateVisited;
                accepted++;
            } else {
                rejected++;
            }

            // Iter 11 Fix 6: log per iter (tuning data). waste=bezcelowe pętle. cov=miks kategorii.
            log.info("ALNS2 iter={} score={} visited={} cov={} effort={} waste={} corridor={}km T={} {}",
                    new Object[]{iter, String.format("%.1f", candidateScore),
                            candidateVisited.size(), breakdown(candidateVisited, areaCat),
                            Math.round(candidateEffort), sb.repeatCount(),
                            String.format("%.1f", sb.avgCorridorKm()),
                            String.format("%.2f", T),
                            isBest ? "<<NEW BEST>>" : ""});

            T *= params.coolingRate();

            // ADAPTIVE R_NEAR: gdy effort daleko od limitu i mało progress, rozszerz promień
            if (currentEffort < totalLimit * 0.6 && noImprove > 10 && adaptiveRNear < 50.0) {
                adaptiveRNear = Math.min(50.0, adaptiveRNear * 2);
                log.info("ALNS2 adaptive R_NEAR expanded: {} km (effort {}/{}, noImprove={})",
                        new Object[]{adaptiveRNear, Math.round(currentEffort),
                                Math.round(totalLimit), noImprove});
                noImprove = 0; // reset
            }

            if (noImprove >= params.noImproveStop()) {
                log.info("ALNS2 no-improve stop at iter={}", iter);
                break;
            }
        }

        // FINAL: jeden REALNY chunked BRouter na best (target-island handling, dokładna geometria,
        // dystans). SA używał per-edge eval (proxy); tu robimy "prawdziwy" wynik dla orchestration.
        RouteCalculation bestCalc;
        try {
            bestCalc = brouter.apply(bestWps, profile);
        } catch (RuntimeException ex) {
            // Finalny chunked BRouter wywalił się (klaster wysp mimo prune) — NIE failuj planu.
            // Zbuduj wynik z per-edge geometrii (evalRoute ma haversine fallback dla wysp, nie rzuca).
            log.warn("ALNS2 final chunked BRouter failed ({}) — fallback do per-edge geometrii", ex.getMessage());
            EvalResult fb = evalRoute(bestRoute, cache, brouter, profile, params.alphaKmPerMeter(), gminaIndex);
            List<double[]> geom = fb.geometry();
            double km = 0;
            for (int i = 1; i < geom.size(); i++) {
                km += velomarker.service.planning.WaypointSelector.haversineKm(geom.get(i - 1), geom.get(i));
            }
            bestCalc = new RouteCalculation(geom, km);
        }
        brouterCalls++;
        // RECONCILE: dziury blisko REALNEJ finalnej trasy (densify działał na seed-geom, po realnym
        // BRouterze trasa się przesunęła). Diagnostyka pokazała: wszystkie dziury < 15km = recoverable.
        // Wepnij nieodwiedzone ≤R cheapest-insertion (bramka budżetu) i przeroutuj. Mutuje bestRoute.
        bestCalc = reconcileNearRouteHoles(bestRoute, prefs, candidatePool, gminaIndex, bestCalc,
                brouter, profile, params.alphaKmPerMeter(), totalLimit, rewardPerCategory, checkCancel, taskId);
        bestWps = buildWaypoints(bestRoute, prefs);
        // STRICT count: gmina zaliczona dopiero gdy trasa wjeżdża ≥ requiredDepth W GŁĄB (nie otarcie
        // krawędzi). Eliminuje false-positives „dojazdów pod krawędź". Front (turf, uproszczony ~90m)
        // i tak nie liczy otarć → po tej zmianie front i backend się zbliżą.
        Set<Integer> bestVisited = gminaIndex.visitedAreaIds(bestCalc.coordinates());
        if (bestVisited.isEmpty()) bestVisited = bestVisitedIds; // fallback gdyby final calc pusty
        List<UnvisitedArea> visitedAreas = new ArrayList<>();
        for (UnvisitedArea a : candidatePool) {
            if (bestVisited.contains(a.areaId())) visitedAreas.add(a);
        }

        log.info("ALNS2 coverage breakdown: {}", breakdown(bestVisited, areaCat));

        // DIAGNOSTYKA DZIUR: nieodwiedzone gminy wg odległości od FINALNEJ trasy (subsample dla
        // wydajności). Pokazuje czy dziury są BLISKO trasy (do odzyskania densify/reconcile) czy
        // DALEKO/nieroutowalne (prawdziwe wyspy). Steruje wyborem fixu na dziury.
        List<double[]> diagGeom = subsampleGeometry(bestCalc.coordinates(), 4000);
        int h0_3 = 0, h3_6 = 0, h6_15 = 0, h15_plus = 0, totalHoles = 0;
        List<String> holeNames = new ArrayList<>();
        for (UnvisitedArea a : candidatePool) {
            if (bestVisited.contains(a.areaId())) continue;
            totalHoles++;
            double d = gminaIndex.distToRoute(a, diagGeom);
            if (d <= 3.0) h0_3++;
            else if (d <= 6.0) h3_6++;
            else if (d <= 15.0) h6_15++;
            else h15_plus++;
            if (holeNames.size() < 60) holeNames.add(a.name() + "(" + Math.round(d) + "km)");
        }
        log.info("ALNS2 hole diagnostics: {} dziur / {} pool → distToRoute 0-3km:{}, 3-6km:{}, 6-15km:{}, >15km:{}",
                new Object[]{totalHoles, candidatePool.size(), h0_3, h3_6, h6_15, h15_plus});
        log.info("ALNS2 hole names (≤60): {}", String.join(", ", holeNames));

        long elapsedMs = System.currentTimeMillis() - startTs;
        log.info("ALNS2 done: bestScore={} bestVisited={} bestEffort={}/{} (~{}%) iters={} brouterCalls={} cacheHits={} cacheRatio={}% accepted={} rejected={} elapsedMs={}",
                new Object[]{String.format("%.1f", bestScore), visitedAreas.size(),
                        Math.round(bestEffort), Math.round(totalLimit),
                        Math.round(bestEffort * 100.0 / totalLimit), iter, brouterCalls,
                        cache.hits(), Math.round(cache.hitRatio() * 100),
                        accepted, rejected, elapsedMs});

        return new Alns2Result(bestCalc, bestWps, visitedAreas, iter, brouterCalls, accepted, rejected);
    }

    // ── SCORE & EFFORT ──────────────────────────────────────────────────────────────────

    /** Rozbicie score na komponenty — do per-iter logowania (Iter 11 Fix 6). */
    record ScoreBreakdown(double total, double base, double holePenalty,
                          double repeatPenalty, double corridorPenalty,
                          int repeatCount, double avgCorridorKm) {}

    /**
     * score = Σ reward(visited) − holePenalty − DELTA×repeat − corridorFactor×Σ excess_dist
     *
     * <p>Iter 11 Fix 3: hole penalty per-kategoria — gęste/małe (reward≤1) płacą BETA (pełna kara),
     * rzadkie/duże (reward>1, np. Kreis) płacą GAMMA &lt; BETA ("dziura mniej boli"). Repeat penalty
     * (DELTA) karze nawroty (segmenty wjeżdżające 2× do tej samej gminy).
     *
     * <p>Iter 11 Fix 1: corridor penalty — punkty trasy daleko od baseline (excess ponad rNear).
     * Samonormujące: trasa blisko korytarza ≈ 0, ekspedycje w bok rosną.
     */
    static ScoreBreakdown scoreDetailed(Set<Integer> visited, List<double[]> route,
                                        List<double[]> geometry, List<UnvisitedArea> pool,
                                        GminaIndex index, Map<String, Double> rewards,
                                        List<double[]> baseline, double rNearKm,
                                        double beta, double gamma, double delta,
                                        double corridorFactor) {
        double base = 0;
        double holePenalty = 0;
        for (UnvisitedArea a : pool) {
            double r = rewards.getOrDefault(rewardCategoryKey(a), 1.0);
            if (visited.contains(a.areaId())) {
                base += r;
            } else if (index.distToRoute(a, route) <= rNearKm) {
                // Iter 11 fix: kara PROPORCJONALNA do reward (nie płaska). Gęsta gmina reward 0.23
                // → dziura 0.23×beta, nie 1.0. Bez tego dziura > nagroda → optymalizator omijał
                // gęste klastry i robił krótką trasę (53% budżetu). beta/gamma = mnożniki na reward.
                holePenalty += r * (r > 1.0 ? gamma : beta);
            }
        }
        int repeatCount = countWastefulRevisits(geometry, index);
        double repeatPenalty = delta * repeatCount;
        double corridorSum = 0;
        int cnt = 0;
        for (int i = 1; i < route.size() - 1; i++) {
            corridorSum += Math.max(0, minDistToBaselineKm(route.get(i), baseline) - rNearKm);
            cnt++;
        }
        double corridorPenalty = corridorFactor * corridorSum;
        double avgCorridor = cnt > 0 ? corridorSum / cnt : 0;
        double total = base - holePenalty - repeatPenalty - corridorPenalty;
        return new ScoreBreakdown(total, base, holePenalty, repeatPenalty, corridorPenalty,
                repeatCount, avgCorridor);
    }

    /**
     * Liczy BEZCELOWE powtórki (user: "karać powtórki celowe/bezcelowe, nie wszystkie").
     *
     * <p>Powtórka = ponowny wjazd do gminy już odwiedzonej (gap &gt; 10 km w cumulative-km).
     * Liczona jako ZŁA tylko jeśli MIĘDZY wyjazdem a ponownym wjazdem trasa NIE zaliczyła żadnej
     * NOWEJ gminy (newSince &lt; MIN_NEW). Czyli ślepa pętla "wjazd do gminy którą już mam, bez
     * zysku". Produktywne podwójne przejazdy (np. korytarz raz północą po jedne gminy, raz
     * południem po inne) mają newSince ≫ 0 → NIE karane.
     *
     * <p>Jeden przebieg po geometrii z grid-lookupem (O(geom)), zamiast O(pool×geom) pointInRing.
     */
    static int countWastefulRevisits(List<double[]> geometry, GminaIndex index) {
        if (geometry == null || geometry.size() < 2) return 0;
        final double GAP_KM = 10.0;
        final int MIN_NEW = 1; // pętla zła gdy 0 nowych gmin od ostatniego kontaktu
        java.util.Map<Integer, Double> lastContactKm = new HashMap<>();
        java.util.Map<Integer, Integer> newCountAtLast = new HashMap<>();
        Set<Integer> firstSeen = new HashSet<>();
        int newCovered = 0;
        int wasteful = 0;
        double cum = 0;
        double[] prev = null;
        for (double[] p : geometry) {
            if (prev != null) cum += velomarker.service.planning.WaypointSelector.haversineKm(prev, p);
            prev = p;
            UnvisitedArea g = index.findGminaForPoint(p[0], p[1]);
            if (g == null) continue;
            int id = g.areaId();
            if (firstSeen.add(id)) {
                newCovered++;
            } else {
                Double lastKm = lastContactKm.get(id);
                if (lastKm != null && cum - lastKm > GAP_KM) {
                    int newSince = newCovered - newCountAtLast.getOrDefault(id, 0);
                    if (newSince < MIN_NEW) wasteful++;
                }
            }
            lastContactKm.put(id, cum);
            newCountAtLast.put(id, newCovered);
        }
        return wasteful;
    }

    /** Per-kategoria licznik coverage: {C2/L10=180, C4/L17=13, C0/sg1=9}. */
    static String breakdown(Set<Integer> visited, Map<Integer, String> areaCat) {
        java.util.Map<String, Integer> m = new java.util.TreeMap<>();
        for (int id : visited) {
            String c = areaCat.get(id);
            if (c != null) m.merge(c, 1, Integer::sum);
        }
        return m.toString();
    }

    /** Min haversine (km) od punktu do downsampled baseline polyline. */
    static double minDistToBaselineKm(double[] p, List<double[]> baseline) {
        if (baseline == null || baseline.isEmpty()) return 0;
        double min = Double.MAX_VALUE;
        for (double[] b : baseline) {
            double d = velomarker.service.planning.WaypointSelector.haversineKm(p, b);
            if (d < min) min = d;
        }
        return min;
    }

    /**
     * effort = distance_km + ALPHA × climb_m. Climb pobieramy z elevation sampler dla pełnej
     * geometrii BRouter (gainM). User: "1 m wzniosu = 0.1 km, niezależnie od profilu".
     *
     * <p>Symetria km↔wznios: region górzysty → mniej km, więcej wzniosu, ten sam effort.
     * Region płaski → więcej km, mało wzniosu, ten sam effort.
     */
    double computeEffortFromCalc(RouteCalculation calc) {
        double km = calc.distanceKm();
        double climbM = 0;
        if (elevation != null) {
            try {
                climbM = elevation.sample(calc.coordinates()).gainM();
            } catch (RuntimeException ignored) {
                // elevation niedostępne — fallback do czystego km (effort będzie underestimated)
            }
        }
        return km + params.alphaKmPerMeter() * climbM;
    }

    // ── DESTROY & REPAIR ────────────────────────────────────────────────────────────────

    /**
     * Random fragment removal. NIE rusza first/last (anchors) ani innych anchors w środku
     * (via points).
     *
     * <p>Iter 11 Fix 4: anti-redundancy constraint — NIE usuwa fragmentu, jeśli powstały skrót
     * A→C (prosta między ocalałymi końcami) przechodzi przez gminy JUŻ ODWIEDZONE gdzie indziej.
     * Bez tego destroy generuje nawroty (BRouter reroutuje A→C przez visited area). Próbuje
     * kilku losowych fragmentów; jeśli każdy łamie constraint → minimalny destroy (1 punkt).
     */
    void destroy(List<double[]> route, List<double[]> anchors, double destroyRatio,
                 GminaIndex index, Set<Integer> visited) {
        if (route.size() < 5) return;
        int removeCount = Math.max(1, (int) Math.round((route.size() - 2) * destroyRatio));
        int maxStart = route.size() - 1 - removeCount;
        if (maxStart <= 1) return;

        double roll = rand.nextDouble();

        // SEGMENT-REVERSAL (40%): odwróć kolejność losowego segmentu — TE SAME obszary, inna trasa
        // łącząca. Nie gubi coverage (kreissitz/Kreis zostają!), odplątuje skrzyżowania/nawrotki
        // których chciwy 2-opt nie ruszy (SA akceptuje też chwilowe pogorszenie). Skrócenie uwalnia
        // effort → repair dorzuca NOWE obszary. To "2-opt z temperaturą".
        if (roll < 0.40) {
            for (int attempt = 0; attempt < 5; attempt++) {
                int i = 1 + rand.nextInt(route.size() - 3);
                // Segment KRÓTKI (≤8). Odwrócone krawędzie wewn. = nowe kierunkowe wpisy cache →
                // BRouter miss. Długi reverse = lawina calli (iteracja 2 min, przekraczał 300s cap).
                int segLen = 2 + rand.nextInt(7); // 2..8
                int j = Math.min(route.size() - 2, i + segLen);
                if (j - i < 2) continue;
                boolean anchorInside = false;
                for (int k = i; k <= j; k++) {
                    if (isAnchor(route.get(k), anchors)) { anchorInside = true; break; }
                }
                if (anchorInside) continue;
                java.util.Collections.reverse(route.subList(i, j + 1));
                return;
            }
            return;
        }

        // WORST-REMOVAL (30%): celuj w najdroższy waypoint = ślepy spur (duży detour: wjazd-wyjazd
        // tą samą drogą). Kierowany destroy daje repair szansę wymienić zły fragment na produktywny.
        if (roll < 0.70) {
            int worst = -1;
            double bestGain = Double.NEGATIVE_INFINITY;
            for (int i = 1; i < route.size() - 1; i++) {
                if (isAnchor(route.get(i), anchors)) continue;
                double gain = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i))
                        + velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1))
                        - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                if (gain > bestGain) { bestGain = gain; worst = i; }
            }
            if (worst > 0) {
                int start = Math.max(1, Math.min(maxStart, worst - removeCount / 2));
                removeFragment(route, start, removeCount, anchors);
                return;
            }
        }

        final int ATTEMPTS = 5;
        int fallbackStart = 1 + rand.nextInt(maxStart - 1);
        int chosenStart = -1;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int start = 1 + rand.nextInt(maxStart - 1);
            int survivingAfter = start + removeCount; // index po fragmencie (ocalały koniec C)
            if (survivingAfter >= route.size()) continue;
            fallbackStart = start;
            if (!segmentCrossesVisited(route.get(start - 1), route.get(survivingAfter), index, visited)) {
                chosenStart = start;
                break;
            }
        }
        // ZAWSZE usuń pełny fragment. W gęstym regionie każdy skrót A→C trafia w odwiedzoną gminę,
        // więc constraint jest tylko PREFERENCJĄ — bez tego destroy spadał do 1-punktu i trasa
        // puchła (repair +20 > destroy +1). Constraint wybiera lepszy fragment jeśli istnieje.
        removeFragment(route, chosenStart >= 0 ? chosenStart : fallbackStart, removeCount, anchors);
    }

    /** Usuwa {@code removeCount} punktów od {@code start}, pomijając anchory (via). */
    private static void removeFragment(List<double[]> route, int start, int removeCount,
                                       List<double[]> anchors) {
        for (int i = 0; i < removeCount && route.size() > 2; i++) {
            int idx = start;
            if (idx >= route.size() - 1) idx = route.size() - 2;
            if (idx <= 0) break;
            if (isAnchor(route.get(idx), anchors)) {
                start++;
                continue;
            }
            route.remove(idx);
        }
    }

    /**
     * Czy prosta A→C (próbkowana co ~3 km) przechodzi przez gminę już odwiedzoną? Heurystyka
     * proxy dla "BRouter reroute A→C zrobi nawrót". Próbkujemy punkty wzdłuż odcinka i sprawdzamy
     * findGminaForPoint ∈ visited.
     */
    private static boolean segmentCrossesVisited(double[] a, double[] c, GminaIndex index,
                                                 Set<Integer> visited) {
        double distKm = velomarker.service.planning.WaypointSelector.haversineKm(a, c);
        int steps = Math.max(2, (int) Math.ceil(distKm / 3.0));
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            double lng = a[0] + (c[0] - a[0]) * t;
            double lat = a[1] + (c[1] - a[1]) * t;
            UnvisitedArea g = index.findGminaForPoint(lng, lat);
            if (g != null && visited.contains(g.areaId())) return true;
        }
        return false;
    }

    private static boolean isAnchor(double[] p, List<double[]> anchors) {
        for (double[] a : anchors) {
            if (Math.abs(a[0] - p[0]) < 1e-6 && Math.abs(a[1] - p[1]) < 1e-6) return true;
        }
        return false;
    }

    /**
     * Repair: znajdź gminy blisko trasy (dist ≤ R_NEAR), policz best insertion delta, sortuj
     * po `reward/delta` DESC, wstaw aż effort limit.
     *
     * <p>OPTYMALIZACJE (user: "moze trzeba to jakos zoptymalizowac by nie napierdalac tak czesto"):
     * <ol>
     *   <li>Haversine pre-filter — odrzuć candidates z `dist > R_NEAR` bez BRouter</li>
     *   <li>Best-edge pre-selection per candidate — znajdź najbliższy edge po haversine,
     *       real BRouter TYLKO dla tego 1 edge'a</li>
     *   <li>Single sample point — wybierz najbliższy edge'a po haversine, BRouter dla niego</li>
     *   <li>Precompute edge_AB info dla wszystkich edges w tour raz</li>
     *   <li>Cache hits dla pairs które się powtarzają między iteracjami SA</li>
     * </ol>
     * Total: ~30-50 BRouter calls per iter zamiast 450+.
     */
    void repair(List<double[]> route, List<double[]> anchors, GminaIndex gminaIndex,
                List<UnvisitedArea> pool, Map<String, Double> rewards,
                double rNearKm, double totalLimit, double alphaKmPerMeter,
                List<double[]> baseline, double corridorFactor,
                EdgeCache cache,
                BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                String profile) {
        // OPCJA C (iter 10 fix): TOP-K candidates po haversine pre-score, real BRouter tylko
        // dla top-K. Cache hit ratio ~64-91% → realnie ~kilkanaście unique calls/iter.
        // TOP_K=40 (było 20): musi dodać ≥ ile destroy usuwa (~destroyRatio×size), inaczej trasa
        // się kurczy co iterację (repair < destroy → erozja coverage).
        final int TOP_K = 40;

        Set<Integer> visited = visitedAreaIdsFromPolyline(route, gminaIndex);

        // Precompute effort dla edges w tour (proxy = haversine × 1.69; SA loop używa cache też).
        // Tu używamy proxy bo nie chcemy BRouter call dla every edge — to OK bo final BRouter
        // eval pełnej trasy w main SA loop sprawdza real effort.
        final double PROXY_MULTIPLIER = 1.69;
        double currentEffortProxy = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            currentEffortProxy += velomarker.service.planning.WaypointSelector.haversineKm(
                    route.get(i), route.get(i + 1)) * PROXY_MULTIPLIER;
        }

        // FAZA 1: pre-score wszystkich candidates po HAVERSINE proxy (taniem)
        record PreScored(UnvisitedArea area, int bestEdgeIdx, double[] bestPoint,
                          double haversineDelta, double reward, double distBase, double proxyRatio) {}
        List<PreScored> preScored = new ArrayList<>();
        for (UnvisitedArea a : pool) {
            if (visited.contains(a.areaId())) continue;
            double distHaver = gminaIndex.distToRoute(a, route);
            if (distHaver > rNearKm) continue;

            double[][] samples = gminaIndex.samplePointsFor(a);
            int bestEdgeIdx = -1;
            double bestHaverDelta = Double.MAX_VALUE;
            double[] bestSample = samples[0];
            for (int i = 0; i < route.size() - 1; i++) {
                double[] A = route.get(i);
                double[] B = route.get(i + 1);
                double dAB = velomarker.service.planning.WaypointSelector.haversineKm(A, B);
                for (double[] p : samples) {
                    double dAX = velomarker.service.planning.WaypointSelector.haversineKm(A, p);
                    double dXB = velomarker.service.planning.WaypointSelector.haversineKm(p, B);
                    double delta = dAX + dXB - dAB;
                    if (delta < bestHaverDelta) {
                        bestHaverDelta = delta;
                        bestEdgeIdx = i;
                        bestSample = p;
                    }
                }
            }
            if (bestEdgeIdx < 0) continue;
            double r = rewards.getOrDefault(rewardCategoryKey(a), 1.0);
            // Iter 11 Fix 1: corridor bias — candidate daleko od baseline dostaje mniejszy ratio.
            double distBase = minDistToBaselineKm(bestSample, baseline);
            double ratio = r / (Math.max(0.01, bestHaverDelta) * (1.0 + distBase * corridorFactor));
            preScored.add(new PreScored(a, bestEdgeIdx, bestSample, bestHaverDelta, r, distBase, ratio));
        }

        // FAZA 2: top-K po proxy ratio DESC, real BRouter tylko dla nich
        preScored.sort(Comparator.comparingDouble((PreScored p) -> -p.proxyRatio));
        int topK = Math.min(TOP_K, preScored.size());

        record Finalist(UnvisitedArea area, double realDelta, int bestPos, double[] bestPoint,
                         double reward, double distBase) {}
        List<Finalist> finalists = new ArrayList<>(topK);
        for (int idx = 0; idx < topK; idx++) {
            PreScored ps = preScored.get(idx);
            double[] A = route.get(ps.bestEdgeIdx);
            double[] B = route.get(ps.bestEdgeIdx + 1);
            // Real BRouter dla 3 unique edges (cache hit ratio ~75-90%)
            double eAX = getEdgeEffort(A, ps.bestPoint, cache, brouter, profile, alphaKmPerMeter);
            double eXB = getEdgeEffort(ps.bestPoint, B, cache, brouter, profile, alphaKmPerMeter);
            double eAB = getEdgeEffort(A, B, cache, brouter, profile, alphaKmPerMeter);
            double realDelta = Math.max(0.01, eAX + eXB - eAB);
            finalists.add(new Finalist(ps.area, realDelta, ps.bestEdgeIdx, ps.bestPoint, ps.reward, ps.distBase));
        }

        // FAZA 3: re-sort finalistów po REAL reward/delta DESC (z corridor bias), greedy insert
        finalists.sort(Comparator.comparingDouble((Finalist f) ->
                -f.reward / (Math.max(0.01, f.realDelta) * (1.0 + f.distBase * corridorFactor))));
        double currentEffort = currentEffortProxy; // start z proxy; real eval w main SA loop
        for (Finalist f : finalists) {
            if (currentEffort + f.realDelta > totalLimit) continue;
            int insertAt = f.bestPos + 1;
            if (insertAt < 0 || insertAt > route.size()) continue;
            route.add(insertAt, f.bestPoint);
            currentEffort += f.realDelta;
            visited.add(f.area.areaId());
        }
    }

    /**
     * SEED przez BASELINE-PROJECTION — O(N log N), zamiast cheapest-insertion O(N × E² × S).
     *
     * <p>Iter 11 perf fix. Stary seed (cheapest insertion) skanował wszystkie N kandydatów ×
     * wszystkie krawędzie trasy (rosnące do ~400) × S sample points per insert → miliardy
     * operacji haversine dla gęstych klastrów (np. 3621 gmin CZ, NN 2,3 km). Zjadał cały
     * budżet czasu (4 min), SA nie dostawał ani jednej iteracji.
     *
     * <p>Nowy algorytm:
     * <ol>
     *   <li>Per area: najbliższy sample point do baseline + jego projekcja (cumKm wzdłuż baseline)
     *       + reward/corridor score.</li>
     *   <li>Sort po score DESC, greedy take aż effort_proxy ≈ targetEffort.</li>
     *   <li>Wybrane + anchory posortuj po projekcji ASC → trasa płynie wzdłuż korytarza
     *       start→via→end, zbierając obszary po drodze.</li>
     * </ol>
     * Korzyść podwójna: szybkość (O(N log N)) + silne trzymanie korytarza (Fix 1).
     */
    void greedySeedRoute(List<double[]> route, List<double[]> anchors, GminaIndex gminaIndex,
                          List<UnvisitedArea> pool, Map<String, Double> rewards,
                          double targetEffort, double alphaKmPerMeter,
                          List<double[]> baseline, double corridorFactor,
                          EdgeCache cache,
                          BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                          String profile) {
        final double ROAD_FACTOR = 1.3;
        final double CLIMB_PER_KM_ESTIMATE = 3.0;
        final double EFFORT_MULTIPLIER = ROAD_FACTOR * (1.0 + CLIMB_PER_KM_ESTIMATE * alphaKmPerMeter);

        // Cumulative km wzdłuż baseline (do projekcji punktów na korytarz).
        int bn = baseline.size();
        double[] baseCum = new double[bn];
        for (int i = 1; i < bn; i++) {
            baseCum[i] = baseCum[i - 1] + velomarker.service.planning.WaypointSelector.haversineKm(
                    baseline.get(i - 1), baseline.get(i));
        }
        // FAZA 1: score + projekcja per area (O(N × S × bn))
        List<SeedSel> scored = new ArrayList<>(pool.size());
        for (UnvisitedArea a : pool) {
            double[][] samples = gminaIndex.samplePointsFor(a);
            double bestDist = Double.MAX_VALUE;
            double[] bestSample = samples[0];
            int bestJ = 0;
            for (double[] s : samples) {
                for (int j = 0; j < bn; j++) {
                    double d = velomarker.service.planning.WaypointSelector.haversineKm(s, baseline.get(j));
                    if (d < bestDist) { bestDist = d; bestSample = s; bestJ = j; }
                }
            }
            double r = rewards.getOrDefault(rewardCategoryKey(a), 1.0);
            double detourEffort = Math.max(0.05, 2.0 * bestDist * EFFORT_MULTIPLIER);
            double score = r / (detourEffort * (1.0 + bestDist * corridorFactor));
            scored.add(new SeedSel(a, bestSample, orderKey(bestSample, baseline, baseCum), score, bestDist));
        }
        // SELEKCJA (czym wypełniamy budżet):
        // - alns2 (projection): najlepsze score (najtańszy detour) — korytarz trzyma je przy linii.
        // - alns3: najbliższe KORYTARZOWI start→meta pierwsze (distBase ASC) → gęsty pas wzdłuż
        //   korytarza, rosnący na boki aż do budżetu. NIE „Hilbert od startu" (szedł w losowym
        //   kierunku geograficznym → plama w złym miejscu). Hilbert zostaje TYLKO do porządkowania
        //   trasy (ciasne 2D zamiatanie pasa, bez zygzaka 1D).
        if (serpentine) {
            // alns3: korytarz (distBase ASC) ALE WAŻONY rewardem. Pusty distBase-only zatapiał
            // rzadkie wartościowe kategorie (DE Kreis/kreissitz reward~2.3) gęstymi tanimi (CZ Obec
            // reward~0.23, 3621 szt. w korytarzu) → trasa zbierała same Czechy, pomijała Niemcy.
            // Klucz distBase/reward: blisko korytarza + wysoki reward = pierwsze. PL (jedna kategoria,
            // reward jednolity) → identyczne z czystym distBase, więc bez regresji.
            scored.sort(Comparator.comparingDouble((SeedSel s) ->
                    s.distBase() / Math.max(0.05, rewards.getOrDefault(rewardCategoryKey(s.area()), 1.0))));
        } else {
            scored.sort(Comparator.comparingDouble((SeedSel s) -> -s.score()));
        }

        // FAZA 2: GROW→(SURGICAL spur-prune)→regrow rounds.
        // GROW: dodawaj wg score, re-order projekcja, 2-opt PRZED pomiarem, rośnij aż effort ≈ target.
        // SURGICAL PRUNE: usuń TYLKO prawdziwe ostrogi = waypoint z dużym lokalnym detourem (>4 km,
        //   czyli realny wjazd-wyjazd w bok) ORAZ gmina pokryta gdzie indziej (count≥2). NIE tnie
        //   wszystkiego co pokryte 2× (to gnało trasę 40km od baseline + dziury). Uwolniony budżet
        //   → kolejny GROW dorzuca z BLISKICH (corridor-aware score) → bez rozjazdu.
        List<double[]> anchorOnly = new ArrayList<>(route);
        List<SeedSel> selected = new ArrayList<>();
        int idx = 0;
        final int BATCH = 20;
        final double SPUR_DETOUR_KM = 4.0;
        // Pasmo budżetu: seed ląduje w [0.90, 0.95]×budget (zostaw SPORY zapas), a densify dopina
        // dziury przy trasie do densifyCeiling. User: greedy nie ma zjadać budżetu
        // dalekimi mackami — niżej celowany seed = więcej miejsca na łatanie dziur.
        final double loBand = targetEffort * 0.90;
        final double hiBand = targetEffort * 0.95;
        // Densify celuje w ~100% (NIE 110%) — rezerwa 10% dla reconcile, który dopina dziury na REALNEJ
        // drodze BRoutera (densify działa na przybliżonej geometrii seeda). Bez rezerwy seed+densify zjadał
        // budżet do 109% i reconcile nie miał czym łatać dziur odsłoniętych po realnym przejeździe.
        final double densifyCeiling = targetEffort * 1.0;
        double realEffort = 0;
        int totalPruned = 0;
        int totalRetried = 0;
        int trimmed = 0;
        // areaId → ile entry-pointów już wypróbowaliśmy (0=route-nearest, 1..N=kolejne sample, N+1=centroid).
        Map<Integer, Integer> entryAttempt = new HashMap<>();
        for (int round = 0; round < 8; round++) {
            while (idx < scored.size()) {
                for (int b = 0; b < BATCH && idx < scored.size(); b++, idx++) selected.add(scored.get(idx));
                rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                Alns2LocalSearch.twoOpt(route);
                realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                if (realEffort >= hiBand) break; // grow do górnej granicy pasma (95%), nie do 100%
            }
            EvalResult ev = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
            Map<Integer, Integer> visits = countVisitsPerArea(ev.geometry(), gminaIndex);
            Set<double[]> toRemove = new HashSet<>(); // identity (double[] nie nadpisuje equals)
            boolean swapped = false;
            int unreachable = 0;
            for (int i = 1; i < route.size() - 1; i++) {
                double[] cur = route.get(i);
                if (isAnchor(cur, anchors)) continue;
                UnvisitedArea g = gminaIndex.findGminaForPoint(cur[0], cur[1]);
                int gv = (g == null) ? 0 : visits.getOrDefault(g.areaId(), 0);
                // Wyspa: BRouter nie dojechał do tego waypointu z którejś strony (failedEdges) —
                // nawet jeśli geometria fallback (prosta) fałszywie „zalicza" gminę (gv>0).
                boolean island = failedEdges.contains(edgeKey(route.get(i - 1), cur))
                        || failedEdges.contains(edgeKey(cur, route.get(i + 1)));
                if (gv == 0 || island) {
                    // NIEOSIĄGALNA: BRouter nie dojechał do tego entry-pointu (ślepa uliczka / brak
                    // mostu / centrum miasta). Ale gmina MOŻE być dostępna INNĄ drogą → próbuj KOLEJNE
                    // entry-pointy: route-nearest → pozostałe sample → CENTROID (najgłębiej). Dopiero
                    // gdy WSZYSTKIE zawiodą → wyspa. Maksymalizuje odzysk (mniej dziur).
                    unreachable++;
                    if (g == null) { toRemove.add(cur); continue; }
                    int id = g.areaId();
                    int att = entryAttempt.getOrDefault(id, 0);
                    double[][] samples = gminaIndex.samplePointsFor(g);
                    int nSamp = samples.length;
                    double[] alt = null;
                    if (att == 0) {
                        alt = sampleNearestToGeometry(samples, cur, ev.geometry()); // od strony trasy
                    } else if (att <= nSamp) {
                        double[] cand = samples[(att - 1) % nSamp];
                        if (cand[0] != cur[0] || cand[1] != cur[1]) alt = cand;     // kolejny sample
                    } else if (att == nSamp + 1) {
                        alt = new double[]{g.lng(), g.lat()};                        // centroid = najgłębiej
                    }
                    if (alt != null) {
                        swapEntryPoint(selected, cur, alt, baseline, baseCum);
                        swapped = true;
                        totalRetried++;
                        entryAttempt.put(id, att + 1);
                    } else if (att <= nSamp + 1) {
                        entryAttempt.put(id, att + 1); // ten kandydat odpadł — spróbuj następnego w kolejnej rundzie
                    } else {
                        toRemove.add(cur); // wyczerpane wszystkie sample + centroid → naprawdę wyspa
                    }
                    continue;
                }
                // REDUNDANTNA ostroga: duży detour + gmina pokryta gdzie indziej (count≥2).
                double detour = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), cur)
                        + velomarker.service.planning.WaypointSelector.haversineKm(cur, route.get(i + 1))
                        - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                if (detour > SPUR_DETOUR_KM && gv >= 2) toRemove.add(cur);
            }
            if (!toRemove.isEmpty() || swapped) {
                log.info("ALNS2 seed prune round {}: removed {} stubs ({} unreachable), retried {} entry-points",
                        new Object[]{round, toRemove.size(), unreachable, totalRetried});
                selected.removeIf(s -> toRemove.contains(s.point()));
                totalPruned += toRemove.size();
                rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                Alns2LocalSearch.twoOpt(route);
                realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
            }
            // BAND: w paśmie → koniec; za dużo → utnij najsłabsze do ≤ hi i koniec; za mało → kolejna
            // runda (grow dobierze więcej + znów prune/2opt/retry). To realizuje agresywny budżet.
            if (realEffort >= loBand && realEffort <= hiBand) break;
            if (realEffort > hiBand) {
                selected.sort(Comparator.comparingDouble(SeedSel::score)); // worst (najtańszy reward/najdalszy) first
                while (realEffort > hiBand && selected.size() > 1) {
                    int rm = Math.min(8, selected.size() - 1);
                    for (int k = 0; k < rm; k++) { selected.remove(0); trimmed++; }
                    rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                    Alns2LocalSearch.twoOpt(route);
                    realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                }
                break;
            }
            // realEffort < loBand → następna runda dobierze więcej; jeśli nie ma czym → koniec
            if (idx >= scored.size() && toRemove.isEmpty() && !swapped) break;
        }

        // DENSIFY: dograb donut-holes (nieodwiedzone gminy BLISKO trasy, mijane bez wjazdu) wymieniając
        // najdalsze od korytarza selected. Seed-only pomija SA-repair, który normalnie by je złapał.
        int densified = densifyNearHoles(route, anchorOnly, selected, pool, gminaIndex, baseline, baseCum,
                cache, brouter, profile, alphaKmPerMeter, densifyCeiling, rewards);
        if (densified > 0) realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);

        log.info("ALNS2 seed ({}): +{} obszarów, removed={} stubs, trimmed={}, densified={}, real effort={}/{} ({}%) [seed 90-95%, densify→100%, rezerwa 10% dla reconcile], route size={}",
                new Object[]{serpentine ? "hilbert" : "projection", selected.size(), totalPruned,
                        trimmed, densified, Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), route.size()});
    }

    /**
     * RECONCILE PO FINALE: wpina nieodwiedzone gminy blisko REALNEJ finalnej trasy. Densify działał na
     * geometrii seeda (z waypointów); po realnym chunked BRouterze trasa się przesuwa, odsłaniając gminy
     * które realna droga mija ≤R, ale ich nie zalicza. Dla każdej (najbliższe pierwsze) cheapest-insertion
     * entry-pointu do {@code route} (zachowuje kolejność 2-opt), z bramką budżetu (effort ≤ 110%), potem
     * re-route. Mutuje {@code route}. Zwraca nową {@link RouteCalculation} (lub wejściową gdy nic nie dodano).
     */
    /**
     * Sumaryczny wznios liczony OKNAMI (≤{@value #CLIMB_WINDOW_PTS} pkt/okno). {@code elevation.sample}
     * capuje liczbę próbek (~500), więc JEDEN sample na 2000+ km alpejskich rażąco zaniża climb — przez
     * to reconcile myślał, że ma budżet i balonował go (do ~179%). Próbkowanie oknami trzyma gęstość
     * ~kilkadziesiąt m/próbkę niezależnie od długości trasy → realny wznios do bramki budżetu.
     */
    private static final int CLIMB_WINDOW_PTS = 400;

    /** Minimalny detour (km „w bok i z powrotem"), by waypoint uznać za DALEKĄ mackę kwalifikującą się
     *  do cięcia w swapie. Próg efektywny = max(tego, 3×mediana detouru) — chroni bliskie gminy. */
    private static final double SWAP_TENTACLE_MIN_KM = 12.0;

    /** Min. ułamek najbliższych sąsiadów świeżo zaliczonych, by uznać obszar za dziurę WEWNĘTRZNĄ
     *  (otoczoną), a nie peryferyjną obwódkę przy trasie. Wspólne dla reconcile i densify. */
    private static final double HOLE_ENCLOSED_FRACTION = 0.5;

    private double accurateClimbM(List<double[]> coords) {
        if (elevation == null || coords == null || coords.size() < 2) return 0;
        double total = 0;
        for (int i = 0; i < coords.size() - 1; i += CLIMB_WINDOW_PTS) {
            int end = Math.min(coords.size(), i + CLIMB_WINDOW_PTS + 1); // +1 overlap dla ciągłości okien
            try {
                total += elevation.sample(coords.subList(i, end)).gainM();
            } catch (RuntimeException ignored) {
                // best-effort: brak DEM dla okna → 0 dla tego fragmentu
            }
        }
        return total;
    }

    /** Klucz współrzędnej (6 miejsc ≈ 10cm) — do identyfikacji macki przy usuwaniu w swapie. */
    private static String coordKeyA(double[] c) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", c[0], c[1]);
    }

    /** Suma rewardu kategorii dla zbioru zaliczonych areaId — WARTOŚĆ pokrycia (nie sam licznik gmin). */
    private static double sumReward(Set<Integer> areaIds, Map<Integer, Double> areaReward) {
        double s = 0;
        for (int id : areaIds) s += areaReward.getOrDefault(id, 1.0);
        return s;
    }

    private RouteCalculation reconcileNearRouteHoles(
            List<double[]> route, RoutePreferences prefs, List<UnvisitedArea> pool,
            GminaIndex gminaIndex, RouteCalculation calc,
            BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
            double alpha, double totalLimit, Map<String, Double> rewards,
            Consumer<UUID> checkCancel, UUID taskId) {
        final double R = 15.0;          // diagnostyka: wszystkie dziury < 15km = recoverable
        final double ceiling = totalLimit * 1.10;
        final double MULT = 1.3;        // detour km → effort proxy (road factor)
        final int MAX_RECONCILE = 5;    // user: więcej passes pozwala dolatać dziury które zostały po iter 0-1
        final int MAX_FIT = 2;          // ile razy skurczyć batch i przeroutować, gdy proxy przestrzeli 110%
        Map<Integer, Integer> attempts = new HashMap<>(); // areaId → ile prób wstawienia (eskalacja entry)
        // Reward per obszar (areaId → wartość kategorii). Swap CHRONI wysokowartościowe (rzadkie DE Kreise),
        // tnie tanie peryferyjne (gęste CZ gminy). Bez tego swap ciął najdłuższą mackę = często cenny powiat.
        Map<Integer, Double> areaReward = new HashMap<>();
        for (UnvisitedArea a : pool) {
            areaReward.put(a.areaId(), rewards.getOrDefault(rewardCategoryKey(a), 1.0));
        }
        for (int rIter = 0; rIter < MAX_RECONCILE; rIter++) {
            checkCancel.accept(taskId);
            List<double[]> geom = subsampleGeometry(calc.coordinates(), 4000);
            Set<Integer> visited = gminaIndex.visitedAreaIds(calc.coordinates()); // STRICT (≥ głębokość)
            double currentEffort = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
            if (currentEffort >= ceiling) break; // już ponad 110% — nic nie dokładamy
            // User: WYPEŁNIAJ budżet do 110% (finał ma być 100-110%). Czyste wstawianie aż do 110%; gdy
            // brak miejsca na bliską dziurę (zwł. DUŻY obszar/prowincję) i swap ON — utnij KUMULACYJNIE
            // kilka najdroższych dalekich macek, by sfinansować wstawkę, wciąż ≤110%. Swap OFF → tylko
            // czyste wstawianie do 110% (co się nie mieści, pomijamy). accurateClimbM trzyma room realnym.
            final double room = ceiling - currentEffort; // zapas do 110%
            // Dziury WEWNĘTRZNE (otoczone świeżo zaliczonymi), najbliższe pierwsze. distToRoute ≤R to tylko
            // TANI pre-filtr wydajności (ile gmin liczyć enclosed); twardy warunek = isEnclosedHole — odsiewa
            // peryferyjną obwódkę z boku trasy (była głównym źródłem fałszywych dziur i przestrzelenia budżetu).
            // Entry-point ESKALUJE per próba ({@link #interiorEntryPoints}).
            record Hole(UnvisitedArea a, double dist) {}
            List<Hole> holes = new ArrayList<>();
            for (UnvisitedArea a : pool) {
                if (visited.contains(a.areaId())) continue;
                double d = gminaIndex.distToRoute(a, geom);
                if (d > R) continue;
                if (!gminaIndex.isEnclosedHole(a, visited, HOLE_ENCLOSED_FRACTION)) continue;
                holes.add(new Hole(a, d));
            }
            if (holes.isEmpty()) break;
            holes.sort(Comparator.comparingDouble(Hole::dist));
            // MACKI do SWAPU: peryferyjne waypointy (duży detour „w bok i z powrotem"). REWARD-AWARE —
            // każda macka zna reward swojego obszaru (findGminaForPoint → areaReward). Sort reward ASC,
            // detour DESC: tniemy NAJTAŃSZE wartościowo, najbardziej peryferyjne najpierw. Wysokowartościowe
            // (rzadkie DE Kreise) chronione; tniemy tanie skrajne (gęste CZ gminy). Owner==null = waypoint
            // nic nie zalicza → reward 0 → tnij first (czysty zysk, usuwa bezużyteczną mackę).
            record Macka(double[] coord, double detourKm, double reward) {}
            List<Macka> macki = new ArrayList<>();
            if (reconcileSwap) {
                List<Macka> all = new ArrayList<>();
                for (int i = 1; i < route.size() - 1; i++) {
                    double det = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i))
                            + velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1))
                            - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                    double[] c = route.get(i);
                    UnvisitedArea owner = gminaIndex.findGminaForPoint(c[0], c[1]);
                    double rw = owner != null ? areaReward.getOrDefault(owner.areaId(), 1.0) : 0.0;
                    all.add(new Macka(c, Math.max(0, det), rw));
                }
                double median = 0;
                if (!all.isEmpty()) {
                    List<Double> ds = all.stream().map(Macka::detourKm).sorted().toList();
                    median = ds.get(ds.size() / 2);
                }
                double threshold = Math.max(SWAP_TENTACLE_MIN_KM, 3 * median);
                macki = all.stream()
                        .filter(m -> m.detourKm() >= threshold)
                        .sorted(Comparator.comparingDouble(Macka::reward)
                                .thenComparing((a, b) -> Double.compare(b.detourKm(), a.detourKm())))
                        .collect(java.util.stream.Collectors.toList());
            }
            java.util.Set<String> removeKeys = new java.util.HashSet<>();
            List<double[]> snapshot = new ArrayList<>(route); // rollback gdy re-route padnie
            // Wstawione entry-pointy W KOLEJNOŚCI wstawiania (dziury sortowane dist ASC → najtańsze pierwsze,
            // najdroższe na końcu). Skurcz-i-ponów zdejmuje OGON (najdroższe) gdy proxy przestrzeli 110%.
            List<double[]> insertedPts = new ArrayList<>();
            int inserted = 0;
            int swapped = 0;
            double usedEffort = 0; // łączny przyrost effortu (pure-grow + netto swapów); trzymany ≤ room
            for (Hole h : holes) {
                int att = attempts.merge(h.a().areaId(), 1, Integer::sum) - 1; // prób PRZED tą
                List<double[]> eps = interiorEntryPoints(h.a(), att, geom, gminaIndex);
                if (eps.isEmpty()) continue;
                double[] ep0 = eps.get(0);
                int bestI = -1;
                double bestDetour = Double.MAX_VALUE;
                for (int i = 0; i < route.size() - 1; i++) {
                    double det = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), ep0)
                            + velomarker.service.planning.WaypointSelector.haversineKm(ep0, route.get(i + 1))
                            - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
                    if (det < bestDetour) { bestDetour = det; bestI = i; }
                }
                double intra = 0; // wewnętrzne odległości klastra (do oszacowania kosztu)
                for (int k = 1; k < eps.size(); k++) {
                    intra += velomarker.service.planning.WaypointSelector.haversineKm(eps.get(k - 1), eps.get(k));
                }
                double detEffort = Math.max(0.05, (bestDetour + intra) * MULT);
                double available = room - usedEffort;
                if (detEffort <= available) {
                    // mieści się do 110% bez cięcia (pure-grow)
                    for (int k = 0; k < eps.size(); k++) route.add(bestI + 1 + k, eps.get(k));
                    insertedPts.addAll(eps);
                    usedEffort += detEffort;
                    inserted += eps.size();
                } else if (reconcileSwap) {
                    // brak miejsca → utnij KUMULACYJNIE tanie peryferyjne macki aż zwolnią potrzebny zapas.
                    // TWARDY FILTR: tylko macki o reward ≤ reward DZIURY — nie poświęcamy obszaru cenniejszego
                    // niż łatany (chroni duże DE Kreise przed cięciem na rzecz tanich CZ dziurek).
                    double holeReward = rewards.getOrDefault(rewardCategoryKey(h.a()), 1.0);
                    double need = detEffort - Math.max(0, available);
                    List<double[]> toCut = new ArrayList<>();
                    double cut = 0;
                    for (Macka cand : macki) {
                        if (cut >= need) break;
                        if (cand.reward() > holeReward) continue;           // chroń obszary wartościowsze niż dziura
                        if (removeKeys.contains(coordKeyA(cand.coord()))) continue;
                        toCut.add(cand.coord());
                        cut += cand.detourKm() * MULT;
                    }
                    if (cut < need) continue;                               // za mało taniego budżetu → pomiń dziurę
                    for (double[] c : toCut) removeKeys.add(coordKeyA(c));
                    for (int k = 0; k < eps.size(); k++) route.add(bestI + 1 + k, eps.get(k));
                    insertedPts.addAll(eps);
                    usedEffort += detEffort - cut;                          // netto (cut ≥ need → usedEffort ≤ room)
                    inserted += eps.size();
                    swapped += toCut.size();
                }
                // else: swap OFF i brak miejsca → pomiń dziurę
            }
            if (inserted == 0) break;
            // Usuń wymienione macki (po współrzędnej; anchory 0/last nietknięte).
            if (!removeKeys.isEmpty()) {
                List<double[]> filtered = new ArrayList<>(route.size());
                for (int i = 0; i < route.size(); i++) {
                    if (i == 0 || i == route.size() - 1 || !removeKeys.contains(coordKeyA(route.get(i)))) {
                        filtered.add(route.get(i));
                    }
                }
                route.clear();
                route.addAll(filtered);
            }
            // Usuń sąsiadujące (prawie) zdublowane punkty — BRouter rzuca "Index -1" na zero-długich
            // segmentach (np. wstawiony centroid bliski istniejącemu wp). Anchory (0, last) nietknięte.
            for (int i = route.size() - 2; i >= 1; i--) {
                if (velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1)) < 0.002) {
                    route.remove(i);
                }
            }
            // FIT LOOP (skurcz-i-ponów): przeroutuj; gdy REALNY effort przebije 110% (proxy zaniżył),
            // NIE poddawaj się — zdejmij OGON najdroższych wstawionych dziur i przeroutuj ponownie (max
            // MAX_FIT razy). Macki (cuts) zostają wycięte. Dopiero gdy nie da się zmieścić → rollback+break.
            RouteCalculation next = null;
            double nextEffort = 0;
            boolean rerouteFailed = false;
            int fitTries = 0;
            List<double[]> remainingInserts = new ArrayList<>(insertedPts); // najdroższe na końcu
            while (true) {
                checkCancel.accept(taskId);
                try {
                    next = brouter.apply(buildWaypoints(route, prefs), profile);
                } catch (RuntimeException ex) {
                    rerouteFailed = true;
                    log.warn("ALNS2 reconcile re-route failed ({}) — rollback, koniec reconcile", ex.getMessage());
                    break;
                }
                nextEffort = next.distanceKm() + alpha * accurateClimbM(next.coordinates());
                if (nextEffort <= ceiling) break;                 // mieści się ≤110%
                if (fitTries >= MAX_FIT || remainingInserts.isEmpty()) break; // wyczerpane próby → guard zrobi rollback
                // SKURCZ: zdejmij ~25% najdroższych (ostatnio wstawionych) entry-pointów z route i ponów.
                int drop = Math.max(1, remainingInserts.size() / 4);
                java.util.Set<String> dropKeys = new java.util.HashSet<>();
                for (int t = 0; t < drop && !remainingInserts.isEmpty(); t++) {
                    dropKeys.add(coordKeyA(remainingInserts.remove(remainingInserts.size() - 1)));
                }
                List<double[]> shrunk = new ArrayList<>(route.size());
                for (int i = 0; i < route.size(); i++) {
                    if (i == 0 || i == route.size() - 1 || !dropKeys.contains(coordKeyA(route.get(i)))) {
                        shrunk.add(route.get(i));
                    }
                }
                route.clear();
                route.addAll(shrunk);
                inserted = Math.max(0, inserted - drop);
                fitTries++;
            }
            if (rerouteFailed) {
                route.clear();
                route.addAll(snapshot); // rollback — zostaw poprzedni stan
                break;
            }
            // HARD CAP + GWARANCJA WARTOŚCI: zweryfikuj REALNY effort (dokładny climb oknami) ORAZ że łączny
            // REWARD pokrycia nie spadł istotnie. Liczymy reward (nie sam licznik) — swap świadomie tnie kilka
            // tanich peryferyjnych gmin na rzecz dziur wewnętrznych (licznik może spaść o parę), ale NIE wolno
            // mu skasować wartościowych obszarów. Próg ≤3% straty rewardu = anti-katastrofa: dawny licznikowy
            // guard przepuszczał „1 duży Kreis → 2 tanie dziury" (licznik rósł, wartość leciała w dół).
            Set<Integer> afterIds = gminaIndex.visitedAreaIds(next.coordinates());
            double rewardBefore = sumReward(visited, areaReward);
            double rewardAfter = sumReward(afterIds, areaReward);
            if (nextEffort > ceiling || rewardAfter < rewardBefore * 0.97) {
                route.clear();
                route.addAll(snapshot);
                log.info("ALNS2 reconcile iter {}: ODRZUCAM — effort {}/{} (cap110, fitTries {}), reward {} → {} (rollback)",
                        new Object[]{rIter, Math.round(nextEffort), Math.round(totalLimit), fitTries,
                                String.format(java.util.Locale.ROOT, "%.1f", rewardBefore),
                                String.format(java.util.Locale.ROOT, "%.1f", rewardAfter)});
                break;
            }
            log.info("ALNS2 reconcile iter {}: wstawiono {} dziur (otoczone), ucięto {} macek (swap reward-aware), fitTries {}, realKm {} → {}, effort {}/{}, visited → {}",
                    new Object[]{rIter, inserted, swapped, fitTries, Math.round(calc.distanceKm()),
                            Math.round(next.distanceKm()), Math.round(nextEffort), Math.round(totalLimit), afterIds.size()});
            calc = next;
        }
        return calc;
    }

    /**
     * Punkty wjazdu dla reconcile, ESKALUJĄCE wg liczby prób ({@code att}): att 0 → ½ drogi od
     * edge-sample do centroidu (płytko, tanio); att ≥1 → centroid (głęboko). Multi-point klaster
     * świadomie usunięty — uparte gminy mają nieroutowalne wnętrze (jeziora/lasy), klaster nie pomagał,
     * tylko wydłużał czas i powodował rollback.
     */
    private List<double[]> interiorEntryPoints(UnvisitedArea a, int att, List<double[]> geom, GminaIndex gminaIndex) {
        double[][] samples = gminaIndex.samplePointsFor(a);
        double cx = a.lng(), cy = a.lat();
        if (att >= 1) return List.of(new double[]{cx, cy}); // centroid
        double[] edge = sampleNearestToGeometry(samples, null, geom);
        if (edge == null) edge = samples.length > 0 ? samples[0] : new double[]{cx, cy};
        double f = 0.5;
        return List.of(new double[]{edge[0] + (cx - edge[0]) * f, edge[1] + (cy - edge[1]) * f});
    }

    /**
     * Wypełnia donut-holes: nieodwiedzone gminy w promieniu HOLE_R od trasy. Dodaje je (entry-point od
     * strony trasy = mały detour); gdy brak miejsca w budżecie — WYMIENIA z selected najdalszym od
     * korytarza (efort-neutralnie: tendrils daleko → gminy w rdzeniu). Zwraca ile dograno/wymieniono.
     */
    private int densifyNearHoles(List<double[]> route, List<double[]> anchorOnly, List<SeedSel> selected,
                                 List<UnvisitedArea> pool, GminaIndex gminaIndex, List<double[]> baseline,
                                 double[] baseCum, EdgeCache cache,
                                 BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
                                 double alpha, double ceiling, Map<String, Double> rewards) {
        final double HOLE_R = 6.0;
        final double MULT = 1.69; // haversine → effort proxy (jak w seedzie)
        final double BASE_COST = 2.5; // bazowy koszt każdego nowego waypointu (wjazd/wyjazd, krzywizna)
        int filled = 0;
        for (int pass = 0; pass < 6; pass++) {
            EvalResult ev = evalRoute(route, cache, brouter, profile, alpha, gminaIndex);
            // Hard-stop: jeśli REALNY effort już osiągnął sufit, nie dorzucaj więcej (proxy potrafił
            // zaniżać per-dziura przy gęstym regionie → balon ponad 110%). Liczymy real raz na pass.
            double realAtPass = routeEffortViaCache(route, cache, brouter, profile, alpha);
            if (realAtPass >= ceiling) break;
            // Dystans dziur liczymy do REALNEJ geometrii drogi (subsample ~2000 pkt dla wydajności),
            // NIE do prostej polilinii waypointów — droga BRoutera wije się i muska gminy, które prosta
            // linia omija. Bez tego densify nie widział dziur przy faktycznej trasie (user: "jeździ
            // niedaleko z dwóch stron i mógłby bez problemu wjechać").
            List<double[]> roadGeom = subsampleGeometry(ev.geometry(), 2000);
            Set<Integer> covered = new HashSet<>(ev.visited());
            for (SeedSel s : selected) covered.add(s.area().areaId());
            // Dziura WEWNĘTRZNA (otoczona świeżo zaliczonymi), nie peryferyjna obwódka. distToRoute ≤ HOLE_R
            // to tani pre-filtr wydajności; twardy warunek = isEnclosedHole (≥ułamek najbliższych zaliczonych).
            List<UnvisitedArea> holes = new ArrayList<>();
            for (UnvisitedArea a : pool) {
                if (covered.contains(a.areaId())) continue;
                if (gminaIndex.distToRoute(a, roadGeom) > HOLE_R) continue;
                if (!gminaIndex.isEnclosedHole(a, covered, HOLE_ENCLOSED_FRACTION)) continue;
                holes.add(a);
            }
            if (holes.isEmpty()) break;
            // Kolejność łatania WAŻONA rewardem: dist/reward ASC. Gęste tanie (CZ Obec reward~0.23)
            // nie wyprzedzają rzadkich wartościowych (DE Kreis reward~2.3) tylko dlatego że są bliżej.
            // PL (jedna kategoria) → reward jednolity → sortowanie po samej odległości (bez zmian).
            holes.sort(Comparator.comparingDouble(a ->
                    gminaIndex.distToRoute(a, roadGeom)
                            / Math.max(0.05, rewards.getOrDefault(rewardCategoryKey(a), 1.0))));
            // MACKI: selected wg lokalnego detouru w trasie DESC (odjazd pod Siedlce = duży detour).
            record Exc(SeedSel sel, double detEffort) {}
            java.util.Map<double[], SeedSel> bySel = new java.util.IdentityHashMap<>();
            for (SeedSel s : selected) bySel.put(s.point(), s);
            List<Exc> exc = new ArrayList<>();
            for (int i = 1; i < route.size() - 1; i++) {
                SeedSel s = bySel.get(route.get(i));
                if (s == null) continue; // anchor
                double det = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i))
                        + velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1))
                        - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                exc.add(new Exc(s, det * MULT));
            }
            exc.sort(Comparator.comparingDouble((Exc e) -> -e.detEffort()));
            double effort = realAtPass;
            int swapPtr = 0;
            int passFilled = 0;
            Set<SeedSel> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (UnvisitedArea h : holes) {
                double[] ep = sampleNearestToGeometry(gminaIndex.samplePointsFor(h), null, ev.geometry());
                if (ep == null) ep = gminaIndex.samplePointsFor(h)[0];
                double db = minDistToBaselineKm(ep, baseline);
                // delta = bazowy koszt waypointu + proxy detouru. Bez BASE_COST proxy zaniżał (1.69 vs
                // realne ~5/dziura) → przy 150+ dziurach balon do 116%. Z bazą bliżej realu + pass hard-stop.
                double delta = BASE_COST + Math.max(0.05, 2.0 * gminaIndex.distToRoute(h, roadGeom) * MULT);
                if (effort + delta <= ceiling) {
                    selected.add(new SeedSel(h, ep, orderKey(ep, baseline, baseCum), 1.0, db));
                    effort += delta; passFilled++;
                } else {
                    // brak miejsca → wymień NAJDROŻSZĄ mackę (największy detour) na tę dziurę,
                    // o ile macka kosztuje więcej niż wstawka dziury (czyli realnie zyskujemy).
                    while (swapPtr < exc.size() && removed.contains(exc.get(swapPtr).sel())) swapPtr++;
                    if (swapPtr < exc.size() && exc.get(swapPtr).detEffort() > delta) {
                        Exc victim = exc.get(swapPtr++);
                        selected.remove(victim.sel());
                        removed.add(victim.sel());
                        effort -= victim.detEffort();
                        selected.add(new SeedSel(h, ep, orderKey(ep, baseline, baseCum), 1.0, db));
                        effort += delta; passFilled++;
                    } else {
                        break; // brak macki droższej niż dziura → koniec wymian w tym passie
                    }
                }
            }
            if (passFilled == 0) break;
            filled += passFilled;
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            Alns2LocalSearch.twoOpt(route);
        }
        return filled;
    }

    /** Równomierny subsample gęstej geometrii do ~n punktów (zachowuje pierwszy i ostatni). */
    private static List<double[]> subsampleGeometry(List<double[]> geom, int n) {
        if (geom == null || geom.size() <= n) return geom;
        int step = Math.max(1, geom.size() / n);
        List<double[]> out = new ArrayList<>(n + 1);
        for (int i = 0; i < geom.size(); i += step) out.add(geom.get(i));
        double[] last = geom.get(geom.size() - 1);
        if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        return out;
    }

    /** Kandydat seeda: obszar + entry-point + klucz porządkowania (proj/Hilbert) + score + dist do baseline. */
    private record SeedSel(UnvisitedArea area, double[] point, double proj, double score, double distBase) {}

    /**
     * Wybierz entry-point (sample) gminy NAJBLIŻSZY realnej geometrii trasy — czyli od strony,
     * którą szlak faktycznie przejeżdża (najpewniej z drogą). Pomija aktualny (nieosiągalny) punkt.
     */
    private static double[] sampleNearestToGeometry(double[][] samples, double[] cur, List<double[]> geometry) {
        if (samples == null || samples.length == 0 || geometry == null || geometry.isEmpty()) return null;
        int step = Math.max(1, geometry.size() / 500); // subsample dla szybkości
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] s : samples) {
            if (s == cur) continue;
            double d = Double.MAX_VALUE;
            for (int k = 0; k < geometry.size(); k += step) {
                double dd = velomarker.service.planning.WaypointSelector.haversineKm(s, geometry.get(k));
                if (dd < d) d = dd;
            }
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /** Podmień entry-point danej gminy w {@code selected} (identity po starym punkcie) + przelicz sortKey. */
    private void swapEntryPoint(List<SeedSel> selected, double[] oldPoint, double[] newPoint,
                                List<double[]> baseline, double[] baseCum) {
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).point() == oldPoint) {
                SeedSel old = selected.get(i);
                selected.set(i, new SeedSel(old.area(), newPoint, orderKey(newPoint, baseline, baseCum),
                        old.score(), minDistToBaselineKm(newPoint, baseline)));
                return;
            }
        }
    }

    /** Buduje route = anchory + selected entry-pointy posortowane wg {@link #orderKey} (projekcja lub serpentyna). */
    private void rebuildOrderedRoute(List<double[]> route, List<double[]> anchorOnly,
                                     List<SeedSel> selected, List<double[]> baseline, double[] baseCum) {
        record RoutePt(double[] p, double key) {}
        List<RoutePt> ordered = new ArrayList<>(anchorOnly.size() + selected.size());
        for (int i = 0; i < anchorOnly.size(); i++) {
            double key = (i == 0) ? Double.NEGATIVE_INFINITY
                    : (i == anchorOnly.size() - 1) ? Double.MAX_VALUE
                    : orderKey(anchorOnly.get(i), baseline, baseCum);
            ordered.add(new RoutePt(anchorOnly.get(i), key));
        }
        for (SeedSel s : selected) ordered.add(new RoutePt(s.point(), s.proj()));
        ordered.sort(Comparator.comparingDouble(RoutePt::key));
        route.clear();
        for (RoutePt rp : ordered) route.add(rp.p());
    }

    /**
     * Klucz układania obszaru wzdłuż trasy.
     * <ul>
     *   <li>PROJECTION (alns2): dystans wzdłuż baseline (cumKm) — trasa płynie korytarzem start→end.</li>
     *   <li>SERPENTINE (alns3): strip*BIG + signed-across (wężyk) — paski wzdłuż baseline, w poprzek
     *       naprzemiennie L/R. Wypełnia GRUBY pas (short-baseline-big-budget) bez dziur. Cienki pas
     *       degeneruje się do projekcji (po jednym pasku).</li>
     * </ul>
     */
    private double orderKey(double[] p, List<double[]> baseline, double[] baseCum) {
        if (!serpentine) {
            // PROJECTION (alns2): dystans wzdłuż baseline.
            return alongAcross(p, baseline, baseCum)[0];
        }
        // HILBERT (alns3): indeks na krzywej Hilberta nad bbox kandydatów. Locality-preserving —
        // sąsiednie indeksy = sąsiednie w 2D → grow zbiera ciągłą plamę (nie rozsiane punkty),
        // niezależnie od kształtu regionu (koło PL / korytarz DE-CZ).
        int n = 1 << HILBERT_ORDER;
        double fx = (bMaxLng > bMinLng) ? (p[0] - bMinLng) / (bMaxLng - bMinLng) : 0.0;
        double fy = (bMaxLat > bMinLat) ? (p[1] - bMinLat) / (bMaxLat - bMinLat) : 0.0;
        int x = (int) Math.max(0, Math.min(n - 1, Math.round(fx * (n - 1))));
        int y = (int) Math.max(0, Math.min(n - 1, Math.round(fy * (n - 1))));
        return (double) hilbertD(n, x, y);
    }

    private static final int HILBERT_ORDER = 16; // grid 65536² per oś

    /** Klasyczny xy2d (Wikipedia): (x,y) → odległość d na krzywej Hilberta rzędu n. */
    private static long hilbertD(int n, int x, int y) {
        long d = 0;
        for (int s = n / 2; s > 0; s /= 2) {
            int rx = (x & s) > 0 ? 1 : 0;
            int ry = (y & s) > 0 ? 1 : 0;
            d += (long) s * s * ((3L * rx) ^ ry);
            // rot(n, x, y, rx, ry)
            if (ry == 0) {
                if (rx == 1) { x = n - 1 - x; y = n - 1 - y; }
                int t = x; x = y; y = t;
            }
        }
        return d;
    }

    /** Bbox centroidów kandydatów (do normalizacji punktów dla Hilberta). */
    private void computeCandidateBbox(List<UnvisitedArea> pool) {
        bMinLng = Double.MAX_VALUE; bMinLat = Double.MAX_VALUE;
        bMaxLng = -Double.MAX_VALUE; bMaxLat = -Double.MAX_VALUE;
        for (UnvisitedArea a : pool) {
            bMinLng = Math.min(bMinLng, a.lng()); bMaxLng = Math.max(bMaxLng, a.lng());
            bMinLat = Math.min(bMinLat, a.lat()); bMaxLat = Math.max(bMaxLat, a.lat());
        }
        if (pool.isEmpty()) { bMinLng = 0; bMinLat = 0; bMaxLng = 1; bMaxLat = 1; }
    }

    /** [dystans wzdłuż baseline (cumKm najbliższego wierzchołka), prostopadły dystans ze znakiem L/R]. */
    private static double[] alongAcross(double[] p, List<double[]> baseline, double[] baseCum) {
        double best = Double.MAX_VALUE;
        int bj = 0;
        for (int j = 0; j < baseline.size(); j++) {
            double d = velomarker.service.planning.WaypointSelector.haversineKm(p, baseline.get(j));
            if (d < best) { best = d; bj = j; }
        }
        int a = Math.max(0, bj - 1);
        int b = Math.min(baseline.size() - 1, bj + 1);
        double[] pa = baseline.get(a);
        double[] pb = baseline.get(b);
        double cross = (pb[0] - pa[0]) * (p[1] - pa[1]) - (pb[1] - pa[1]) * (p[0] - pa[0]);
        double across = best * (cross < 0 ? -1.0 : 1.0);
        return new double[]{baseCum[bj], across};
    }

    /**
     * Liczba ODRĘBNYCH wizyt per gmina (segmenty geometrii w ringu oddzielone gap &gt; 10 km).
     * ≥2 = gmina pokryta w kilku miejscach trasy → jej explicit waypoint jest redundantny (stub
     * można wyrzucić, naturalny przejazd nadal ją zalicza). Jeden przebieg O(geom) grid-lookup.
     */
    static Map<Integer, Integer> countVisitsPerArea(List<double[]> geometry, GminaIndex index) {
        Map<Integer, Integer> visits = new HashMap<>();
        if (geometry == null || geometry.size() < 2) return visits;
        final double GAP_KM = 10.0;
        Map<Integer, Double> lastKm = new HashMap<>();
        double cum = 0;
        double[] prev = null;
        for (double[] p : geometry) {
            if (prev != null) cum += velomarker.service.planning.WaypointSelector.haversineKm(prev, p);
            prev = p;
            UnvisitedArea g = index.findGminaForPoint(p[0], p[1]);
            if (g == null) continue;
            int id = g.areaId();
            Double lk = lastKm.get(id);
            if (lk == null || cum - lk > GAP_KM) visits.merge(id, 1, Integer::sum);
            lastKm.put(id, cum);
        }
        return visits;
    }

    /** Suma real effort wszystkich krawędzi trasy (z EdgeCache — tylko nowe krawędzie = BRouter call). */
    private double routeEffortViaCache(List<double[]> route, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter) {
        prewarmEdges(route, cache, brouter, profile, alphaKmPerMeter);
        double e = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            e += getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).effort();
        }
        return e;
    }

    /**
     * Iteracja 12: policz NIEcache'owane krawędzie trasy RÓWNOLEGLE (do {@code brouterParallelism}
     * naraz = wszystkie workery BRoutera), zamiast sekwencyjnie po jednym (1 worker, 7 stoi).
     * Po tym sekwencyjny przebieg (effort/geom) leci z samych cache-hitów. EdgeCache=ConcurrentHashMap,
     * brouter chunked + elevation thread-safe; bound = semafor BRoutera (max-concurrent) → bez 429.
     */
    private void prewarmEdges(List<double[]> route, EdgeCache cache,
                              BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                              String profile, double alphaKmPerMeter) {
        // Proxy-search: getEdge liczy haversine×grid (tani), a leniwa kalibracja chce 1 realnego
        // probe'a NA komórkę — równoległy pre-warm wywołałby thundering-herd (N edge'y w świeżej
        // komórce → N realnych probe'ów naraz). Sekwencyjny getEdge w eval = 1 probe/komórkę. Skip.
        if (proxyGrid != null) return;
        if (brouterParallelism <= 1 || route.size() < 3) return;
        // Unikalne krawędzie (dedup po kierunkowym kluczu) — każdą policz raz.
        List<double[][]> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < route.size() - 1; i++) {
            double[] a = route.get(i);
            double[] b = route.get(i + 1);
            String k = String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
            if (seen.add(k)) edges.add(new double[][]{a, b});
        }
        if (edges.size() < 2) return;
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(brouterParallelism);
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(edges.size());
            for (double[][] e : edges) {
                futures.add(exec.submit(() -> {
                    gate.acquireUninterruptibly();
                    try { getEdge(e[0], e[1], cache, brouter, profile, alphaKmPerMeter); }
                    catch (RuntimeException ignored) { /* fallback haversine w getEdge */ }
                    finally { gate.release(); }
                }));
            }
            for (var f : futures) { try { f.get(); } catch (Exception ignored) {} }
        }
    }

    /** Projekcja punktu na baseline = cumKm najbliższego wierzchołka baseline. */
    private static double projOnBaseline(double[] p, List<double[]> baseline, double[] baseCum) {
        double best = Double.MAX_VALUE;
        int bestJ = 0;
        for (int j = 0; j < baseline.size(); j++) {
            double d = velomarker.service.planning.WaypointSelector.haversineKm(p, baseline.get(j));
            if (d < best) { best = d; bestJ = j; }
        }
        return baseCum[bestJ];
    }

    /** Wynik ewaluacji trasy przez EdgeCache: effort, zaliczone gminy, sklejona geometria. */
    record EvalResult(double effort, Set<Integer> visited, List<double[]> geometry) {}

    /**
     * Ewaluacja CAŁEJ trasy przez per-edge EdgeCache. Effort = Σ edge.effort, geometria = sklejenie
     * edge geometrii (bez duplikowania punktów łączeń), visited = gminy na sklejonej geometrii.
     *
     * <p>KLUCZOWE dla wydajności: tylko ZMIENIONE krawędzie (destroy/repair) to cache miss → 1 mały
     * BRouter call. Niezmieniona część trasy = cache hit. Zamiast pełnego chunked BRouter (~30-40s)
     * per iter SA, liczymy tylko deltę.
     */
    EvalResult evalRoute(List<double[]> route, EdgeCache cache,
                         BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                         String profile, double alphaKmPerMeter, GminaIndex gminaIndex) {
        prewarmEdges(route, cache, brouter, profile, alphaKmPerMeter);
        List<double[]> geom = new ArrayList<>();
        double effort = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            EdgeCache.EdgeInfo info = getEdge(route.get(i), route.get(i + 1), cache,
                    brouter, profile, alphaKmPerMeter);
            effort += info.effort();
            List<double[]> eg = info.geometry();
            int from = geom.isEmpty() ? 0 : 1; // pomiń zduplikowany punkt łączenia
            for (int k = from; k < eg.size(); k++) geom.add(eg.get(k));
        }
        Set<Integer> visited = gminaIndex.visitedAreaIds(geom);
        return new EvalResult(effort, visited, geom);
    }

    /** EdgeInfo (z geometrią) dla A→B z cache; miss = 1 BRouter call (2-punktowy) + elevation.
     *  Proxy-search: większość krawędzi liczona z haversine×grid (bez BRoutera) — patrz {@link #computeEdgeProxy}. */
    private EdgeCache.EdgeInfo getEdge(double[] A, double[] B, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter) {
        return cache.getOrCompute(A[0], A[1], B[0], B[1], pts -> {
            if (proxyGrid != null) {
                return computeEdgeProxy(pts, brouter, profile, alphaKmPerMeter);
            }
            List<Waypoint> wps = List.of(
                    new Waypoint(pts[0][0], pts[0][1], null),
                    new Waypoint(pts[1][0], pts[1][1], null));
            try {
                RouteCalculation calc = brouter.apply(wps, profile);
                double km = calc.distanceKm();
                double climbM = 0;
                if (elevation != null) {
                    try { climbM = elevation.sample(calc.coordinates()).gainM(); }
                    catch (RuntimeException ignored) {}
                }
                return new EdgeCache.EdgeInfo(km, climbM, km + alphaKmPerMeter * climbM, calc.coordinates());
            } catch (RuntimeException e) {
                // BRouter nie policzył (target-island / brak drogi) → zapamiętaj jako wyspę,
                // żeby seed prune usunął ten waypoint przed finalnym chunked BRouterem (inaczej
                // finalny call wywala się twardo na klastrze wysp → plan FAILED).
                failedEdges.add(edgeKey(pts[0], pts[1]));
                double hav = velomarker.service.planning.WaypointSelector.haversineKm(pts[0], pts[1]);
                return new EdgeCache.EdgeInfo(hav * 1.3, 0, hav * 1.3, List.of(pts[0], pts[1]));
            }
        });
    }

    /**
     * Proxy effort dla krawędzi: haversine × współczynniki regionu ({@link RegionFactorGrid}).
     * LENIWA kalibracja: jeśli komórka regionu wymaga probe'a (niekalibrowana / minął interwał),
     * rób JEDEN realny BRouter call → kalibruj komórkę → zwróć realne (z geometrią). Inaczej proxy:
     * km=hav×fDist, climb=km×fClimbPerKm, geometria = prosty odcinek [A,B] (do approx. visited).
     * Probe-fail → failedEdges (wyspa) + komórka zostaje niekalibrowana (spróbuje następnym razem).
     */
    private EdgeCache.EdgeInfo computeEdgeProxy(double[][] pts,
                                                BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                                String profile, double alpha) {
        double hav = velomarker.service.planning.WaypointSelector.haversineKm(pts[0], pts[1]);
        double midLng = (pts[0][0] + pts[1][0]) / 2.0;
        double midLat = (pts[0][1] + pts[1][1]) / 2.0;
        RegionFactorGrid.Cell cell = proxyGrid.cellFor(midLng, midLat);
        if (hav >= 1e-6 && proxyGrid.needsProbe(cell)) {
            List<Waypoint> wps = List.of(
                    new Waypoint(pts[0][0], pts[0][1], null),
                    new Waypoint(pts[1][0], pts[1][1], null));
            try {
                RouteCalculation calc = brouter.apply(wps, profile);
                double km = calc.distanceKm();
                double climbM = 0;
                if (elevation != null) {
                    try { climbM = elevation.sample(calc.coordinates()).gainM(); }
                    catch (RuntimeException ignored) {}
                }
                proxyGrid.recordReal(cell, km, hav, climbM);
                return new EdgeCache.EdgeInfo(km, climbM, km + alpha * climbM, calc.coordinates());
            } catch (RuntimeException e) {
                // Probe nie przeszedł — sygnał wyspy (best-effort; reszta wysp wyjdzie na finale).
                failedEdges.add(edgeKey(pts[0], pts[1]));
            }
        }
        double km = hav * proxyGrid.fDist(cell);
        double climb = km * proxyGrid.fClimbPerKm(cell);
        proxyGrid.recordProxyUse(cell);
        return new EdgeCache.EdgeInfo(km, climb, km + alpha * climb, List.of(pts[0], pts[1]));
    }

    /** Kierunkowy klucz krawędzi A→B (5 miejsc, ~1m) — spójny z EdgeCache. */
    private static String edgeKey(double[] a, double[] b) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
    }

    /** Effort dla pojedynczego edge — deleguje do {@link #getEdge} (cache spójny z geometrią). */
    private double getEdgeEffort(double[] A, double[] B, EdgeCache cache,
                                  BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                  String profile, double alphaKmPerMeter) {
        return getEdge(A, B, cache, brouter, profile, alphaKmPerMeter).effort();
    }

    /** Punkt p do segmentu A→B w km (haversine projection). */
    static double pointToSegmentHaver(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        double t = len2 < 1e-12 ? 0
                : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
        double[] proj = {a[0] + t * dx, a[1] + t * dy};
        return velomarker.service.planning.WaypointSelector.haversineKm(p, proj);
    }

    private static Set<Integer> visitedAreaIdsFromPolyline(List<double[]> route, GminaIndex index) {
        // Tu używamy uproszczonego point-by-point lookup (jak w GminaIndex.visitedAreaIds)
        Set<Integer> visited = new HashSet<>();
        for (double[] p : route) {
            UnvisitedArea a = index.findGminaForPoint(p[0], p[1]);
            if (a != null) visited.add(a.areaId());
        }
        return visited;
    }

    private static double approxEffort(List<double[]> route, double alphaKmPerMeter) {
        double sum = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            sum += velomarker.service.planning.WaypointSelector.haversineKm(
                    route.get(i), route.get(i + 1));
        }
        return sum;
    }

    // ── SA acceptance ───────────────────────────────────────────────────────────────────

    boolean acceptSA(double newScore, double currentScore, double T) {
        if (newScore > currentScore) return true;
        double delta = newScore - currentScore;
        double prob = Math.exp(delta / Math.max(0.001, T));
        return rand.nextDouble() < prob;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────

    /** Klucz kategorii dla reward: kraj+poziom+specialGroup. */
    static String rewardCategoryKey(UnvisitedArea a) {
        return a.countryId() + ":" + a.levelId() + ":" + a.specialGroupId();
    }

    /** Format human-friendly: "C1/L4" lub "C1/sg5" zamiast "1:4:null". */
    static String formatCategoryKey(String key) {
        String[] parts = key.split(":");
        if (parts.length < 3) return key;
        String country = "C" + parts[0];
        String sg = parts[2];
        if (!"null".equals(sg)) return country + "/sg" + sg;
        return country + "/L" + parts[1];
    }

    /**
     * Reward per kategoria = avg_nearest_neighbor_dist / referenceDistKm (Iter 11 Fix 2).
     *
     * <p>User: "powierzchnia to błąd. liczymy proporcję względem ŚREDNIEJ ODLEGŁOŚCI między
     * jednostkami". Gminy gęste (NN ~7 km) → niski reward (łatwo zebrać dużo). Kreissitz rzadkie
     * (NN ~25 km) → wysoki reward (każdy cenny). Liczone na WYSELEKCJONOWANYCH (bbox pool), bo
     * gęstość zależy od regionu (Kreis zach. DE gęstsze niż wsch.; gminy Mazury > Śląsk).
     */
    Map<String, Double> computeRewardPerCategory(List<UnvisitedArea> pool) {
        Map<String, List<UnvisitedArea>> byCat = new HashMap<>();
        for (UnvisitedArea a : pool) {
            byCat.computeIfAbsent(rewardCategoryKey(a), k -> new ArrayList<>()).add(a);
        }
        Map<String, Double> reward = new HashMap<>();
        StringBuilder logSb = new StringBuilder();
        for (var e : byCat.entrySet()) {
            double nn = GminaIndex.avgNearestNeighborDistKm(e.getValue());
            double r = nn > 0 ? nn / params.rewardReferenceDistKm() : 1.0;
            r = Math.max(0.1, r);
            reward.put(e.getKey(), r);
            if (logSb.length() > 0) logSb.append(", ");
            logSb.append(formatCategoryKey(e.getKey())).append("=")
                    .append(String.format("%.2f", r))
                    .append(" (NN ").append(String.format("%.1f", nn)).append("km, n=")
                    .append(e.getValue().size()).append(")");
        }
        log.info("ALNS2 reward per category (refDist={}km): {{{}}}",
                new Object[]{params.rewardReferenceDistKm(), logSb});
        return reward;
    }

    /** Downsample geometry żeby route nie była ogromna (BRouter daje 10k+ coords). */
    static List<double[]> downsample(List<double[]> coords, int target) {
        if (coords == null || coords.size() <= target) return new ArrayList<>(coords);
        List<double[]> result = new ArrayList<>(target);
        double step = (double) coords.size() / target;
        for (int i = 0; i < target; i++) {
            int idx = Math.min(coords.size() - 1, (int) Math.round(i * step));
            result.add(coords.get(idx));
        }
        // Always include last
        if (!result.isEmpty() && result.get(result.size() - 1) != coords.get(coords.size() - 1)) {
            result.set(result.size() - 1, coords.get(coords.size() - 1));
        }
        return result;
    }

    /** Build waypoints z route punktów. Pierwszy = start, ostatni = end, środek = inserted points. */
    static List<Waypoint> buildWaypoints(List<double[]> route, RoutePreferences prefs) {
        List<Waypoint> wps = new ArrayList<>(route.size());
        for (int i = 0; i < route.size(); i++) {
            double[] p = route.get(i);
            String name = (i == 0 && prefs.start() != null) ? prefs.start().name()
                    : (i == route.size() - 1 && prefs.end() != null) ? prefs.end().name()
                    : null;
            wps.add(new Waypoint(p[0], p[1], name));
        }
        return wps;
    }
}
