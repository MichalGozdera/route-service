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

    /** RUNDA 47: powody failów BRoutera (target-island / not-mapped / timeout / other) → ile. Diagnostyka
     *  „czemu wyspa". Concurrent (równoległy pre-warm). Czyszczone per plan(). */
    private final java.util.Map<String, Integer> brouterFailReasons = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fabryka JTS coverage indexu (PEŁNA geometria, plain intersect) — budowana per plan() nad pulą. */
    private final AreaCoverageIndexFactory coverageFactory;
    /** Reconcile-swap: gdy brak budżetu na łatanie dziury, utnij najdroższą „mackę" (daleki waypoint
     *  z dużym detourem) i wstaw w to miejsce bliską dziurę (netto w budżecie). application.yml:
     *  planning.alns2.reconcile-swap. Wyłączony → reconcile tylko rośnie do 100% i staje. */
    private final boolean reconcileSwap;
    /** ADMIN DEBUG: gdy true, loguje GeoJSON snapshot trasy na każdej fazie seeda (init/batch/prune/densify/
     *  reconcile/final). Domyślnie OFF — per-batch zalewa log. application.yml: planning.alns2.debug-geojson. */
    private final boolean debugGeoJson;
    /** A/B: stara pętla DEEP-BATCH po seedzie. Default OFF — zastąpiona przez seed→105% + tailPrune
     *  (deep-batch = 7 iter × pełne reroute ≈ 23 min dla marginalnych zysków; psuł i naprawiał).
     *  application.yml: planning.alns2.deep-batch. Po potwierdzeniu eksperymentu → skasować razem
     *  z deepBatchRefine/cleanRoute. */
    private final boolean deepBatch;
    /** WIGGLE za flagą, default OFF (v3.13): 3 runy danych — ~540 pytań za ~190 effortu za każdym
     *  razem (0.3/call). application.yml: planning.alns2.wiggle. */
    private final boolean wiggleEnabled;
    // ADMIN DEBUG: kontekst do linii ROUTE-STATS (budżet/gminy/kategorie) — stashowany per plan() gdy debugGeoJson.
    private double debugBudget;
    private GminaIndex debugGminaIndex;
    private Map<Integer, String> debugAreaCat;

    /** {@code serpentine=true} = ALNS3 space-filling (HILBERT); {@code proxySearch} = proxy-effort. */
    public AlnsCoveragePlanner2(Alns2Parameters params, ElevationDataSource elevation,
                                boolean serpentine, double stripKm, int brouterParallelism,
                                boolean seedOnly, boolean proxySearch, double proxyCellDeg,
                                int proxyRecalibrateEvery, boolean reconcileSwap,
                                AreaCoverageIndexFactory coverageFactory, boolean debugGeoJson,
                                boolean deepBatch, boolean wiggleEnabled) {
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
        this.debugGeoJson = debugGeoJson;
        this.deepBatch = deepBatch;
        this.wiggleEnabled = wiggleEnabled;
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
                             List<double[]> baselineGeom,
                             RoutePreferences prefs,
                             String profile,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal,
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
        brouterFailReasons.clear();
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
        if (debugGeoJson) { debugBudget = totalLimit; debugGminaIndex = gminaIndex; debugAreaCat = areaCat; }

        // INITIAL ROUTE = shortest path (start, [via...], end)
        List<Waypoint> initialWps = new ArrayList<>();
        initialWps.add(prefs.start());
        if (prefs.via() != null) initialWps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) initialWps.add(prefs.start());
        else if (prefs.end() != null) initialWps.add(prefs.end());
        else initialWps.add(prefs.start());

        // Iter 11 Fix 1: baseline geometry (shortest path start→via→end) — corridor anchor.
        // Punkty trasy daleko od tej linii płacą karę (repair ranking + seed + score).
        // PERF: baseline policzony JUŻ w orkiestracji (computeBaseline) — REUŻYWAMY (te same anchory,
        // ten sam profil) zamiast routować BRouterem drugi raz (w Alpach ~50s/trasa). Świeży routing
        // tylko gdy orkiestracja podała degenerat (BRouter baseline padł → anchory-only, <2 pkt).
        int brouterCalls = 0;
        List<double[]> baseline;
        if (baselineGeom != null && baselineGeom.size() >= 2) {
            baseline = downsample(baselineGeom, 200);
        } else {
            RouteCalculation initialCalc = brouter.apply(initialWps, profile);
            brouterCalls = 1;
            baseline = downsample(initialCalc.coordinates(), 200);
        }
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
        log.info("ALNS2 greedy seed: budget effort={} (v3.8: init-grow + compact-loop ≤8 cykli: grow→2opt→anchor→enclosed→tailPrune[in-memory]→topup)",
                new Object[]{Math.round(seedTarget)});
        greedySeedRoute(route, anchors, gminaIndex, candidatePool, rewardPerCategory,
                seedTarget, params.alphaKmPerMeter(), baseline, effCorridor,
                cache, brouter, profile);

        // BEZ post-seed twoOpt/relocate (v3.3): seed kończy się po pełnym 2opt ostatniego cyklu
        // COMPACT-LOOP; późniejsze przetasowanie tworzyło NIEPOSPRZĄTANE ogonki (wp133 — relocate
        // po ostatnim tailPrune, nikt już nie czyścił). Trasa z seeda = finalna kolejność.

        // EVAL przez EdgeCache (per-edge, NIE pełny chunked BRouter). Pierwsza ewaluacja populuje
        // cache wszystkich ~N krawędzi; kolejne iteracje SA liczą tylko ZMIENIONE krawędzie.
        EvalResult seedEval = evalRoute(route, cache, brouter, profile, params.alphaKmPerMeter(), gminaIndex);
        brouterCalls += (int) cache.realCalls(); // v3.18: realne brouter.apply (nie misses — te liczą sliced-seedy)

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

        // RUNDA 40: pokaż effort DOKŁADNY (spójny z ROUTE-STATS seed-real) jako główny; w nawiasie wewn. przybliżony
        // (Σ per-leg climb, evalRoute) używany dalej w reconcile. To rozwiewa „111 czy 101%": realny ślad = dokładny.
        double accAfterSeed = routeEffortAccurate(route, cache, brouter, profile, params.alphaKmPerMeter());
        log.info("ALNS2 after seed (+2opt): route_size={} effort={}/{} ({}%, dokładny; wewn.przybliżony={}) visited={} score={}",
                new Object[]{route.size(), Math.round(accAfterSeed), Math.round(totalLimit),
                        Math.round(accAfterSeed * 100.0 / totalLimit), Math.round(currentEffort),
                        currentVisited.size(), String.format("%.1f", currentScore)});
        if (!brouterFailReasons.isEmpty()) // RUNDA 47: agregat failów BRoutera po seedzie (per powód + ile krawędzi)
            log.info("ALNS2 BRouter-FAILS (seed): {} | unikalnych krawędzi z failem={}",
                    new Object[]{brouterFailReasons, failedEdges.size()});

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
            long realBefore = cache.realCalls();
            EvalResult candEval = evalRoute(candidate, cache, brouter, profile,
                    params.alphaKmPerMeter(), gminaIndex);
            brouterCalls += (int) (cache.realCalls() - realBefore);
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
        // DEEP-BATCH LOOP (C): A/B za flagą planning.alns2.deep-batch, default OFF. Zastąpiony przez
        // seed→[100,105%] + tailPrune w greedySeedRoute (deep-batch = 7 iter × pełne reroute ≈ 23 min
        // dla marginalnych zysków; resnap-do-cięciwy psuł pokrycie, heal naprawiał własne szkody).
        if (deepBatch) {
            bestCalc = deepBatchRefine(bestRoute, prefs, candidatePool, gminaIndex, bestCalc,
                    brouter, profile, params.alphaKmPerMeter(), totalLimit, rewardPerCategory, anchors, checkCancel, taskId);
            bestWps = buildWaypoints(bestRoute, prefs);
        }
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

        // FINAL effort = REAL po wszystkich reconcile iter'ach (km z BRoutera + alpha × wznios oknami).
        // Stara wartość `bestEffort` zostawała z seeda przed reconcile → user widział 101% w logu
        // gdy faktycznie reconcile dociągnął np. do 110%. Liczymy raz na koniec dla prawdziwego obrazu.
        double finalEffort = bestCalc.distanceKm() + params.alphaKmPerMeter() * accurateClimbM(bestCalc.coordinates());
        long elapsedMs = System.currentTimeMillis() - startTs;
        log.info("ALNS2 done: bestScore={} bestVisited={} bestEffort={}/{} (~{}%) iters={} brouterCalls={} cacheHits={} cacheRatio={}% accepted={} rejected={} elapsedMs={}",
                new Object[]{String.format("%.1f", bestScore), visitedAreas.size(),
                        Math.round(finalEffort), Math.round(totalLimit),
                        Math.round(finalEffort * 100.0 / totalLimit), iter, brouterCalls,
                        cache.hits(), Math.round(cache.hitRatio() * 100),
                        accepted, rejected, elapsedMs});

        // FINAL RECOMPUTE: wszystkie wewnetrzne wywołania brouter.apply (~10k+ per coverage plan)
        // używaja computeStats=false (oszczednosc CPU). Tu robimy JEDEN ostatni call z brouterFinal
        // (computeStats=true) żeby zwracany RouteCalculation miał pełne stats (surface/road/smoothness +
        // spans) — bez tego orchestrator slicing per dzien daje puste mapy/spans.
        try {
            RouteCalculation finalBestCalc = brouterFinal.apply(bestWps, profile);
            log.info("ALNS2 final recompute z stats: distanceKm={} stats.totalMeters={}",
                    new Object[]{Math.round(finalBestCalc.distanceKm()),
                            finalBestCalc.stats() != null ? finalBestCalc.stats().totalMeters() : 0});
            bestCalc = finalBestCalc;
            brouterCalls++;
        } catch (RuntimeException ex) {
            log.warn("ALNS2 final recompute z stats failed ({}) — zwracam wynik bez stats", ex.getMessage());
        }
        // ADMIN DEBUG: finalna realna geometria trasy + ponumerowane waypointy
        if (debugGeoJson) {
            List<double[]> finalWps = new ArrayList<>(bestWps.size());
            for (Waypoint w : bestWps) finalWps.add(new double[]{w.lng(), w.lat()});
            debugGeometry("final-real", bestCalc.coordinates(), finalWps, bestCalc.distanceKm());
        }
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
                // Pełna granulacja sample = 1:1 per coord, bez zaniżania wzniosu (default cap 500 dla
                // długich tras gubił 30%+ climb na samplingu). User: „effort z pełnej geometrii, nie sampli".
                climbM = elevation.sample(calc.coordinates(), calc.coordinates().size()).gainM();
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
            // RUNDA 51: punkt gminy = NAJGŁĘBSZY (środek MIC) — deterministyczny, ZAWSZE wewnątrz gminy (sample
            // granica−500m bywał poza gminą → „gmina bez wp, sąsiad z 2"). distBase/detour/score/orderKey wszystko z niego.
            double[] deep = gminaIndex.deepestInteriorPoint(a.areaId());
            if (deep == null) deep = gminaIndex.samplePointsFor(a)[0]; // fallback gdy MIC null
            double bestDist = Double.MAX_VALUE;
            for (int j = 0; j < bn; j++) {
                double d = velomarker.service.planning.WaypointSelector.haversineKm(deep, baseline.get(j));
                if (d < bestDist) bestDist = d;
            }
            double r = rewards.getOrDefault(rewardCategoryKey(a), 1.0);
            double detourEffort = Math.max(0.05, 2.0 * bestDist * EFFORT_MULTIPLIER);
            double score = r / (detourEffort * (1.0 + bestDist * corridorFactor));
            scored.add(new SeedSel(a, deep, orderKey(deep, baseline, baseCum), score, bestDist));
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
        // Pasmo budżetu (v3): INIT-GROW celuje w [1.00, 1.05]×budget, potem COMPACT-LOOP
        // {2opt → tailPrune → grow-near} domyka do ~100% bez dziur i ogonków. Zastępuje
        // DEEP-BATCH oraz wielorundowy grow ze scored-tail (dalekie gminy, skoki 90%→128%).
        final double hiBand = targetEffort * 1.05;
        // INIT-GROW zbiera do 110% budżetu; nadmiar ściąga COMPACT-LOOP/TRIM do pasma [95,105]%.
        final double growCeiling = targetEffort * 1.10;
        double realEffort = 0;
        int totalPruned = 0;
        int totalRetried = 0;
        int trimmed = 0;
        // INSTRUMENTACJA: rozbicie wall-time seeda na fazy (nasz single-thread vs routeEffort=BRouter wall).
        // routeEffortNs zawiera RÓWNOLEGŁY prewarm BRoutera; reszta to czysto nasz single-thread.
        long tRebuildNs = 0, tTwoOptNs = 0, tRouteEffortNs = 0, tEvalNs = 0, tVisitsNs = 0, tPruneNs = 0;
        // L2 (cięcie churnu): w grow effort SZACUJEMY z haversine×kmFactor + alpha×Δelev×climbFactor (tanio,
        // bez BRoutera), a realny routeEffort robimy tylko CO CHECKPOINT_EVERY batchy (rekalibracja factorów)
        // lub gdy est zbliża się do pasma (confirm-before-stop). Tnie ~8500 calli do ~2-3k. Reszta faz = REAL.
        final int CHECKPOINT_EVERY = 5;
        // Jeden effort-factor: realEffort / Σhaversine, rekalibrowany na checkpoincie. (DEM/climb-factor
        // usunięte — climbFactor wychodził ~8.6, czyli prosta-DEM nic nie wnosiła; jeden factor jest prostszy
        // i równie dobry, bo i tak służy tylko do pasma budżetu.) Init = EFFORT_MULTIPLIER (road×(1+climb·alpha)).
        double effortFactor = EFFORT_MULTIPLIER;
        int realCheckpoints = 0;
        // areaId → próba entry-pointu (0=route-nearest, 1=centroid, ≥2=wyspa, usuń). Skróciliśmy z 10 prób
        // (route-nearest + N sampli + centroid) do 2 — wzorzec interiorEntryPoints reconcile. Empirycznie
        // sample[1..N] rzadko się udają gdy route-nearest padł (te same krawędzie boundary). Cięcie ~80%
        // retry'ów per stubborn-gmina × ~2 edges per retry = duża redukcja BRouter calls (z ~140k na ~50k).
        Map<Integer, Integer> entryAttempt = new HashMap<>();
        // Cache findGminaForPoint per (lng,lat) — w prune wywoływane dla wp + prev + next,
        // a wp powtarzają się między rundami (5 rund × 30k wp = 450k JTS lookups w FR).
        // Klucz 6 cyfr = ~10 cm precyzji (jak EdgeCache). Pure function (lng,lat→Area), więc
        // cache stabilny przez wszystkie rundy.
        Map<String, UnvisitedArea> gminaPointCache = new HashMap<>();
        java.util.function.Function<double[], UnvisitedArea> findGminaCached = pt -> gminaPointCache.computeIfAbsent(
                String.format(java.util.Locale.ROOT, "%.6f,%.6f", pt[0], pt[1]),
                k -> gminaIndex.findGminaForPoint(pt[0], pt[1]));
        // Progress reporting: dla mega-scope (Francja 35k komun) seed trwa 3-6h.
        // User nie wie czy plan dalej pracuje czy wisi. Logujemy co PROGRESS_EVERY obszarów
        // + na początku każdej rundy → user widzi ETA na podstawie tempa.
        final int PROGRESS_EVERY = 500;
        final long seedStartTs = System.currentTimeMillis();
        int lastProgressMilestone = 0;
        log.info("ALNS2 seed grow START: pool={} obszarów, target effort={} ({}/dzień × {}d)",
                new Object[]{scored.size(), Math.round(targetEffort),
                        Math.round(targetEffort / Math.max(1, route.size())), route.size()});
        cache.setReason("grow"); // v3.16: INIT-GROW = realne waypointy (księgowanie strzałów per powód)
        debugSkeleton("init", route); // ADMIN DEBUG: start+meta+anchory (przed dorzucaniem gmin)
        // INIT-GROW (v3): JEDNA runda grow ze scored (baseline-score) do pasma + islands-fix.
        // Dalsze dobieranie przejmuje COMPACT-LOOP (grow-near po distToRoute = zwartość bloba) —
        // scored-tail po pierwszym prune dawał DALEKIE gminy (skoki 90%→128%) i błędne koło psuj-naprawiaj.
        final int round = 0;
        {
            long roundStartTs = System.currentTimeMillis();
            int roundStartSelected = selected.size();
            log.info("ALNS2 seed round {} START: dotąd dodano {} obszarów, effort={}/{} ({}%), elapsed={}s",
                    new Object[]{round, selected.size(), Math.round(realEffort), Math.round(targetEffort),
                            realEffort > 0 ? Math.round(realEffort * 100.0 / targetEffort) : 0,
                            (System.currentTimeMillis() - seedStartTs) / 1000});
            int batchCounter = 0;
            while (idx < scored.size()) {
                // PRECYZJA: od 80% budżetu (est ≥ 0.80×target) zmniejsz batch 20→6 i przejdź na real +
                // pełny 2opt CO BATCH (dokładny pomiar długości dróg w terenie zanim dobijemy do 110%).
                // Poniżej 80% rośniemy szybko (batch 20, est, real tylko na checkpoincie).
                boolean precise = routeHaversineKm(route) * effortFactor >= targetEffort * 0.80;
                int batchSize = precise ? 6 : BATCH;
                for (int b = 0; b < batchSize && idx < scored.size(); b++, idx++) {
                    selected.add(scored.get(idx));
                }
                long _tReb = System.nanoTime();
                rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                tRebuildNs += System.nanoTime() - _tReb;
                // 2-opt incremental po każdym batchu (window ±80 od końca trasy = lokalne ulepszenia),
                // PEŁNY twoOpt co 5 batchy = 100 obszarów = global cleanup. Dla FR 34746 obszarów / 5
                // batchy 20 = 347 pełnych twoOpt zamiast 1737 → ~5× szybsze (z 4h 16min do ~1-1.5h
                // łącznie z routeEffortViaCache). Dla małych scope (≤500 wp) twoOpt(route) i tak
                // wewnętrznie robi pełen skan (FULL_SCAN_MAX), więc bez regresji.
                batchCounter++;
                long _tTwo = System.nanoTime();
                if (precise || batchCounter % 5 == 0) {
                    twoOptLogged(route, "init-grow-batch" + batchCounter);
                } else {
                    Alns2LocalSearch.twoOptIncremental(route, route.size() - 1, 80);
                }
                tTwoOptNs += System.nanoTime() - _tTwo;
                // L2: tani estymator effortu = Σhaversine × effortFactor. Realny BRouter tylko na checkpoincie
                // lub gdy est zbliża się do pasma → confirm-before-stop (nie przerywamy na samym szacunku).
                double hav = routeHaversineKm(route);
                double estEffort = hav * effortFactor;
                // batchCounter==1: wczesna kalibracja effortFactor (init 1.69 zaniża → bez tego małe plany
                // przestrzeliwały do 129% zanim 1. checkpoint trafił w %5). Potem co CHECKPOINT_EVERY.
                boolean doReal = (batchCounter == 1) || (batchCounter % CHECKPOINT_EVERY == 0) || estEffort >= hiBand
                        || precise; // od 80% budżetu real co batch (est bywa nieświeży po prune)
                if (doReal) {
                    long _tRE = System.nanoTime();
                    realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                    tRouteEffortNs += System.nanoTime() - _tRE;
                    realCheckpoints++;
                    if (hav > 1) effortFactor = realEffort / hav;   // rekalibruj jeden factor (km+wznios+detour razem)
                    // ADMIN DEBUG: na checkpoincie cache krawędzi jest CIEPŁY → złóż realną geometrię dróg (cache-hity, 0 nowych calli) + waypointy
                    if (debugGeoJson) debugGeometry("round" + round + "-batch" + batchCounter + "-real",
                            concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                            routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
                } else {
                    realEffort = estEffort;
                }
                debugSkeleton("round" + round + "-batch" + batchCounter, route); // ADMIN DEBUG: szkielet (kolejność+numery) po każdym batchu
                // Progress co 500 obszarów (user widzi tempo i może oszacować ETA dla mega-scope)
                int currentMilestone = selected.size() / PROGRESS_EVERY;
                if (currentMilestone > lastProgressMilestone) {
                    lastProgressMilestone = currentMilestone;
                    long elapsedS = (System.currentTimeMillis() - seedStartTs) / 1000;
                    double pct = realEffort * 100.0 / targetEffort;
                    double avgSecPerArea = elapsedS / (double) Math.max(1, selected.size());
                    int remainingToTarget = (int) Math.max(0, (targetEffort - realEffort) / Math.max(0.01, realEffort / Math.max(1, selected.size())));
                    long etaS = (long) (remainingToTarget * avgSecPerArea);
                    log.info("ALNS2 seed progress: +{} obszarów (round={}), effort={}/{} ({}%), elapsed={}s, tempo={}ms/area, eta≈{}min",
                            new Object[]{selected.size(), round, Math.round(realEffort), Math.round(targetEffort),
                                    Math.round(pct), elapsedS, Math.round(avgSecPerArea * 1000), etaS / 60});
                }
                if (realEffort >= growCeiling) { // grow do 110% budżetu; COMPACT-LOOP/TRIM ściągnie do pasma [95,105]%
                    log.info("ALNS2 INIT-GROW: osiągnięto {}% (≥110%) → stop rundy 0",
                            Math.round(realEffort * 100.0 / targetEffort));
                    break;
                }
            }
            long roundDurMs = System.currentTimeMillis() - roundStartTs;
            log.info("ALNS2 seed round {} END: dodano {} obszarów w tej rundzie ({} → {}), trwało {}s",
                    new Object[]{round, selected.size() - roundStartSelected, roundStartSelected, selected.size(), roundDurMs / 1000});
            // ISLANDS-FIX (v3, max 3 przebiegi): TYLKO wyspy/nieosiągalne. Stary transit-bet prune
            // (lollipop/spur + restore 25 km) WYCIĘTY — robił dziury (restore promieniowy nie trafiał,
            // grow nie umiał wrócić) i thrashował (removed 6/restored 37). Cięciem ogonków zajmuje się
            // wyłącznie tailPrune v2 w COMPACT-LOOP (realna geometria, weryfikacja przed akceptacją).
            for (int islPass = 0; islPass < 3; islPass++) {
                long _tEval = System.nanoTime();
                EvalResult ev = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
                tEvalNs += System.nanoTime() - _tEval;
                realEffort = ev.effort();
                long _tVis = System.nanoTime();
                Map<Integer, Integer> visits = countVisitsPerArea(ev.geometry(), gminaIndex);
                tVisitsNs += System.nanoTime() - _tVis;
                Set<double[]> toRemove = new HashSet<>(); // wyspy (identity — double[] nie nadpisuje equals)
                boolean swapped = false;
                int unreachable = 0;
                long _tPrune = System.nanoTime();
                for (int i = 1; i < route.size() - 1; i++) {
                    double[] cur = route.get(i);
                    if (isAnchor(cur, anchors)) continue;
                    UnvisitedArea g = findGminaCached.apply(cur);
                    int gv = (g == null) ? 0 : visits.getOrDefault(g.areaId(), 0);
                    // Wyspa: BRouter nie dojechał do tego waypointu z którejś strony (failedEdges) —
                    // nawet jeśli geometria fallback (prosta) fałszywie „zalicza" gminę (gv>0).
                    boolean island = failedEdges.contains(edgeKey(route.get(i - 1), cur))
                            || failedEdges.contains(edgeKey(cur, route.get(i + 1)));
                    if (gv != 0 && !island) continue;
                    // NIEOSIĄGALNA: próba 0: route-nearest sample (płytko, od strony trasy).
                    // Próba 1: centroid (najgłębiej). Wyczerpane → wyspa, usuń.
                    unreachable++;
                    if (g == null) { toRemove.add(cur); continue; }
                    int id = g.areaId();
                    int att = entryAttempt.getOrDefault(id, 0);
                    double[] alt = null;
                    if (att == 0) {
                        double[][] samples = gminaIndex.samplePointsFor(g);
                        alt = sampleNearestToGeometry(samples, cur, ev.geometry()); // od strony trasy
                    } else if (att == 1) {
                        alt = gminaIndex.deepestInteriorPoint(g.areaId());           // RUNDA 51: najgłębszy (nie centroid)
                        if (alt == null) alt = new double[]{g.lng(), g.lat()};
                    }
                    if (alt != null && (alt[0] != cur[0] || alt[1] != cur[1])) {
                        swapEntryPoint(selected, cur, alt, baseline, baseCum);
                        swapped = true;
                        totalRetried++;
                        entryAttempt.put(id, att + 1);
                    } else if (att <= 1) {
                        entryAttempt.put(id, att + 1); // ta próba odpadła (np. alt==cur) — następny pass
                    } else {
                        toRemove.add(cur); // route-nearest + centroid wyczerpane → naprawdę wyspa
                    }
                }
                tPruneNs += System.nanoTime() - _tPrune;
                if (toRemove.isEmpty() && !swapped) break; // czysto — koniec passów
                selected.removeIf(s -> toRemove.contains(s.point()));
                totalPruned += toRemove.size();
                rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                twoOptLogged(route, "init-grow-islands-prune");
                realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                log.info("ALNS2 seed islands pass {}: removed={}, unreachable={}, retried={} entry-points (total)",
                        new Object[]{islPass, toRemove.size(), unreachable, totalRetried});
            }
            // ADMIN DEBUG: stan po INIT-GROW + islands (przed COMPACT-LOOP)
            debugSkeleton("round0-grown", route);
            if (debugGeoJson) debugGeometry("round0-grown-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
        }

        // COMPACT-LOOP v3.17b — pętla PROPORCJONALNA (decyzja usera: koniec topup/holefill/headroom).
        // Po INIT-GROW: {2opt → anchorTransit → enclosedFill → tailPrune (tnie ogonki, ZWALNIA budżet —
        // tanie dzięki relokacji JTS v3.17a) → ZMIERZ G gmin @ E%} → jeśli E<95% dobierz
        // additional = round(G×(1−E)/E) wg score (w pamięci, jeden pomiar) i powtórz, aż 95-105%.
        // Proporcję liczymy ze stanu ZWARTEGO (po prune — spury sprzed cięcia zawyżają koszt/gminę).
        // Pętla ZAWSZE kończy prune→anchorTransit (każda gmina = waypoint NA śladzie → 2opt nie gubi
        // tranzytu = fix Brzezin).
        int growNearInserted = 0;
        int enclosedFilled = 0;
        // Zmiana 3: STRZAŁY/plan per faza (suma + Δfaza); wątkowane przez seed, gated debugGeoJson.
        Map<String, Long> shots = new HashMap<>();
        if (debugGeoJson) shots = logShots("init-grow", shots, cache);
        for (int cycle = 0; cycle < 8; cycle++) {
            long cycleCallsStart = cache.realCalls(); // v3.18: realne strzały (nie misses)
            // v3.20: cycle-start 2opt USUNIĘTY — był NO-OP (INIT-GROW + grow/enclosed/prune 2opt-ują
            // wewnętrznie; run dowiódł cycle0-2opt-real == round0-grown-real co do joty). Debug zostaje
            // jako snapshot WEJŚCIA cyklu (ten sam stan, jaśniejsza nazwa `entry`).
            if (debugGeoJson) debugGeometry("cycle" + cycle + "-entry-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-entry", shots, cache); // RUNDA 24: STRZAŁY na start cyklu
            // RUNDA 24: ANCHOR-INTERSECTS — GŁÓWNY silnik pokrycia (raz na cykl). Reset wszystkich DOTYKANYCH gmin:
            //    wp na PIERWSZYM wejściu w rdzeń (≥200m) albo CENTROID (muśnięcie). Kończy 2opt. enclosedFill NIE tu —
            //    tylko na końcu seeda (holefill). Realny BRouter wchodzi dopiero w tailPruneJts (reroute przez nowe wp).
            anchorResetTouched(route, selected, anchorOnly, anchors, baseline, baseCum, cache,
                    brouter, profile, alphaKmPerMeter, gminaIndex, pool, "cycle" + cycle);
            if (debugGeoJson) debugGeometry("cycle" + cycle + "-anchor-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-anchor", shots, cache);
            // tailPrune — DOPIERO TERAZ BRouter (reroute przez nowe wp) + tnij ogonki DO SKUTKU (bez wewn. anchora)
            int prunePasses = cycle == 0 ? 8 : 3;
            realEffort = doubleCut(route, selected, anchors, baseline, baseCum, // RUNDA 69: cut→2opt→reroute→cut (dla pewności)
                    cache, brouter, profile, alphaKmPerMeter, gminaIndex, findGminaCached,
                    pool, targetEffort, prunePasses,
                    "cycle" + cycle + "-tailprune-real");
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-cut", shots, cache);
            // 5. ZMIERZ stan ZWARTY (gminy + effort z cache — 0 BRoutera)
            EvalResult evc = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
            realEffort = routeEffortAccurate(route, cache, brouter, profile, alphaKmPerMeter); // RUNDA 40: DOKŁADNY (spójny z ROUTE-STATS), nie Σ przybliżony evc.effort()
            int gmin = evc.visited().size();
            double eFrac = realEffort / targetEffort;
            // 6. EXIT w paśmie [95%,105%]
            if (eFrac >= 0.95 && eFrac <= 1.05) {
                log.info("ALNS2 COMPACT cycle {}: anchor-intersects → 2opt → BRouter → tailPrune → {} gmin @ {}% → KONIEC (w paśmie), calls={}",
                        new Object[]{cycle, gmin, Math.round(eFrac * 100), cache.realCalls() - cycleCallsStart});
                break;
            }
            // 7. >105% → przerwij; TRIM końcowy (poniżej) utnie najsłabsze
            if (eFrac > 1.05) {
                log.info("ALNS2 COMPACT cycle {}: {} gmin @ {}% > 105% → TRIM (poniżej pętli), calls={}",
                        new Object[]{cycle, gmin, Math.round(eFrac * 100), cache.realCalls() - cycleCallsStart});
                break;
            }
            // 8. PROPORCJA: dobierz additional = round(G×(1−E)/E) wg score (w pamięci, jeden pomiar)
            //    — np. 200 gmin @ 80% → +50; 245 @ 96% → +10. growNear z limitem maxInserts.
            int additional = (int) Math.round(gmin * (1.0 - eFrac) / Math.max(0.05, eFrac));
            int added = 0;
            if (additional > 0) {
                cache.setReason("grow");
                GrowNearResult gr = growNear(route, selected, anchorOnly, baseline, baseCum, cache,
                        brouter, profile, alphaKmPerMeter, gminaIndex, pool, rewards, hiBand,
                        Math.min(additional, 16), additional + 1, additional);
                added = gr.inserted();
                growNearInserted += added;
                realEffort = gr.effort();
            }
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-grow", shots, cache);
            if (debugGeoJson) debugGeometry("cycle" + cycle + "-grow-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            log.info("ALNS2 COMPACT cycle {}: anchor-intersects → 2opt → BRouter → tailPrune → {} gmin @ {}% → +{} (proporcja do 100%, cel +{}), calls={}",
                    new Object[]{cycle, gmin, Math.round(eFrac * 100), added, additional,
                            cache.realCalls() - cycleCallsStart});
            if (added == 0) break; // brak kandydatów do dobrania — koniec
        }

        // RUNDA 71 — FAZA TRIM (PO cyklach, gdy >105%): PEELING peryferii wg reward/koszt-objazdu, w pamięci,
        // BEZ kotwiczenia między cięciami. Tnij tylko NIE-otoczone (allNeighborsVisited=false → bez dziur).
        // Po wewn. peelingu (proporcja do 100%, pełny 2-opt + realny BRouter co rundę) → anchor + doubleCut RAZ.
        // Dawniej: sort distBase DESC (geometria, ślepy na reward) + anchor MIĘDZY cięciami (odkotwiczał z powrotem)
        // + pomiar PRZED 2-opt (surowa projekcja = śmieci). Patrz plan sp-jrz-na-to-robisz-quizzical-comet.
        for (int ti = 0; ti < 4 && realEffort > hiBand; ti++) {
            int peeledThisRound = 0;
            // WEWN. PEELING: tnij proporcjonalną porcję najgorszego reward/detour (fringe), pełny 2-opt, zmierz BRouterem.
            for (int peelK = 1; peelK <= TRIM_MAX_PEELS && realEffort > hiBand; peelK++) {
                long peelCallsStart = cache.realCalls();
                double before = realEffort;
                EvalResult evt = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
                Set<Integer> visited = evt.visited();
                int gmint = visited.size();
                double eFracT = realEffort / targetEffort;
                int removeN = Math.max(1, (int) Math.round(gmint * (eFracT - 1.0) / eFracT));
                // kandydaci = nie-enclosed, nie-protected, FRINGE (cięcie nie zrobi dziury); klucz = reward/koszt-objazdu ASC
                record DealCand(SeedSel s, double key) {}
                List<DealCand> cands = new ArrayList<>();
                for (SeedSel s : selected) {
                    if (s.score() >= ENCLOSED_PROTECTED_SCORE) continue;
                    if (gminaIndex.allNeighborsVisited(s.area().areaId(), visited)) continue; // otoczona z każdej strony śladem (też zza granicy) → cięcie zrobiłoby dziurę
                    int wpIdx = identityIndexOf(route, s.point());
                    if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue; // brzeg/anchor — nie kandydat
                    double[] prev = route.get(wpIdx - 1), cur = route.get(wpIdx), next = route.get(wpIdx + 1);
                    double eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter).distanceKm();
                    double eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter).distanceKm();
                    double detour = Math.max(0.0, eIn + eOut
                            - velomarker.service.planning.WaypointSelector.haversineKm(prev, next));
                    double rw = rewards.getOrDefault(rewardCategoryKey(s.area()), 1.0);
                    cands.add(new DealCand(s, rw / Math.max(TRIM_DETOUR_EPS, detour))); // niski klucz = zły deal = tnij pierwszy
                }
                if (cands.isEmpty()) break; // nic bezpiecznego do ucięcia (sama peryferia-wnętrze) → przerwij peeling
                cands.sort(Comparator.comparingDouble(DealCand::key));
                cache.setReason("pomiar");
                int removed = 0;
                for (DealCand dc : cands) {
                    if (removed >= removeN) break;
                    int wpIdx = identityIndexOf(route, dc.s().point());
                    if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
                    route.remove(wpIdx);        // W MIEJSCU (bez rebuildOrderedRoute) → prev→next wprost, brak rozłażenia
                    selected.remove(dc.s());
                    trimmed++; removed++; peeledThisRound++;
                }
                if (removed == 0) break;
                twoOptLogged(route, "trim" + ti + "-peel" + peelK);                 // PEŁNY 2-opt (haversine, tylko skraca)
                realEffort = routeEffortAccurate(route, cache, brouter, profile, alphaKmPerMeter); // realny BRouter PO 2-opt
                log.info("ALNS2 TRIM peel ti={} k={}: {} gmin @ {}% → -{} (reward/detour, fringe) → {}%, calls={}",
                        new Object[]{ti, peelK, gmint, Math.round(eFracT * 100), removed,
                                Math.round(realEffort / targetEffort * 100), cache.realCalls() - peelCallsStart});
                if (debugGeoJson) debugGeometry("trim" + ti + "-peel" + peelK + "-real",
                        concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                        routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
                if (debugGeoJson) shots = logShots("trim" + ti + "-peel" + peelK, shots, cache);
                if (realEffort >= before - TRIM_PROGRESS_EPS) break; // anty-spin: ucięto kolateral/tanie, effort nie schudł
            }
            // RAZ na rundę: przywróć invariant „gmina=wp na śladzie" + autorytatywna geometria (METODY BEZ ZMIAN)
            anchorResetTouched(route, selected, anchorOnly, anchors, baseline, baseCum, cache,
                    brouter, profile, alphaKmPerMeter, gminaIndex, pool, "trim" + ti);
            if (debugGeoJson) debugGeometry("trim" + ti + "-anchor-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            if (debugGeoJson) shots = logShots("trim" + ti + "-anchor", shots, cache);
            realEffort = doubleCut(route, selected, anchors, baseline, baseCum,
                    cache, brouter, profile, alphaKmPerMeter, gminaIndex, findGminaCached,
                    pool, targetEffort, 3, "trim" + ti + "-tailprune-real");
            // zjechało <95% → dobierz (reward-aware growNear, BEZ ZMIAN)
            double eFracAfter = realEffort / targetEffort;
            if (eFracAfter < 0.95) {
                int gminA = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex).visited().size();
                int additional = (int) Math.round(gminA * (1.0 - eFracAfter) / Math.max(0.05, eFracAfter));
                if (additional > 0) {
                    cache.setReason("grow");
                    GrowNearResult gr = growNear(route, selected, anchorOnly, baseline, baseCum, cache,
                            brouter, profile, alphaKmPerMeter, gminaIndex, pool, rewards, hiBand,
                            Math.min(additional, 16), additional + 1, additional);
                    growNearInserted += gr.inserted();
                    realEffort = gr.effort();
                    cache.setReason("pomiar");
                }
                if (debugGeoJson) debugGeometry("trim" + ti + "-grow-real",
                        concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                        routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
                if (debugGeoJson) shots = logShots("trim" + ti + "-grow", shots, cache);
            }
            if (peeledThisRound == 0) break; // nic nie dało się bezpiecznie uciąć → nie kręć outer loop w kółko
        }

        // RUNDA 69 — HOLEFILL (enclosed-only): złap WSZYSTKIE enclosed (otoczone zaliczonymi dookoła).
        // BRAK → log + KONIEC seeda (świeżo po anchor+2opt+podwójne cięcie). SĄ → wp w NAJGŁĘBSZYM punkcie każdej
        // (WPROST, bez enclosedFill — re-detekcja zbędna) + anchor-intersects + 2opt + podwójne cięcie → KONIEC.
        Set<Integer> visitedHF = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Set<Integer> enclosedHF = gminaIndex.enclosedUnvisited(visitedHF);
        if (enclosedHF.isEmpty()) {
            log.info("ALNS2 HOLEFILL: 0 enclosed → KONIEC seeda (świeżo po anchor+2opt+podwójne cięcie)");
        } else {
            int ef = 0;
            for (UnvisitedArea a : pool) {
                if (!enclosedHF.contains(a.areaId())) continue;
                double[] deep = gminaIndex.deepestInteriorPoint(a.areaId());
                if (deep == null) continue;
                selected.add(new SeedSel(a, deep, orderKey(deep, baseline, baseCum), ENCLOSED_PROTECTED_SCORE,
                        minDistToBaselineKm(deep, baseline)));
                ef++;
            }
            enclosedFilled += ef;
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            log.info("ALNS2 HOLEFILL: {} enclosed → wp najgłębszy + anchor + 2opt + podwójne cięcie", enclosedHF.size());
            if (debugGeoJson) debugGeometry("holefill-enclosed-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            if (debugGeoJson) shots = logShots("holefill-enclosed", shots, cache);
            anchorResetTouched(route, selected, anchorOnly, anchors, baseline, baseCum, cache,
                    brouter, profile, alphaKmPerMeter, gminaIndex, pool, "holefill");
            if (debugGeoJson) debugGeometry("holefill-anchor-real",
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            if (debugGeoJson) shots = logShots("holefill-anchor", shots, cache);
            realEffort = doubleCut(route, selected, anchors, baseline, baseCum,
                    cache, brouter, profile, alphaKmPerMeter, gminaIndex, findGminaCached,
                    pool, targetEffort, 3, "holefill-tailprune-real");
        }
        if (debugGeoJson) shots = logShots("seed-final", shots, cache);
        int densified = growNearInserted + enclosedFilled; // grow-near + ENCLOSED-FILL
        debugSkeleton("seed", route); // ADMIN DEBUG: szkielet końca seeda (przed deep loop)
        if (debugGeoJson) debugGeometry("seed-real", concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));

        log.info("ALNS2 seed ({}): +{} obszarów, removed={} islands, trimmed={}, grow-near={}, retried={} entry-points, real effort={}/{} ({}%) [v3.8: init-grow + compact-loop(grow→2opt→anchor→enclosed→tailPrune→topup)], route size={}",
                new Object[]{serpentine ? "hilbert" : "projection", selected.size(), totalPruned,
                        trimmed, densified, totalRetried,
                        Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), route.size()});
        // INSTRUMENTACJA: rozbicie wall-time seeda. routeEffort = BRouter (równoległy) + sumowanie;
        // rebuild/2opt/eval/visits/prune = NASZ single-thread (multithread go NIE przyspiesza).
        long seedWallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        log.info("ALNS2 seed PHASE BREAKDOWN (wall={}s): routeEffort(BRouter)={}s | rebuild={}s | 2opt={}s | eval={}s | countVisits={}s | prune={}s",
                new Object[]{seedWallS, tRouteEffortNs / 1_000_000_000L, tRebuildNs / 1_000_000_000L,
                        tTwoOptNs / 1_000_000_000L, tEvalNs / 1_000_000_000L, tVisitsNs / 1_000_000_000L,
                        tPruneNs / 1_000_000_000L});
        // L2: ile realnych checkpointów (zamiast real co batch), skalibrowany factor, sumaryczne calle BRoutera.
        log.info("ALNS2 seed L2: realCheckpoints={} effortFactor={} brouterCalls(real)={} (cache misses={} — w tym sliced-seedy)",
                new Object[]{realCheckpoints, String.format(java.util.Locale.ROOT, "%.3f", effortFactor), cache.realCalls(), cache.misses()});
        // v3.16: ROLLUP STRZAŁÓW per powód — realne brouter.apply (NIE misses: misses zawyża o sliced-seedy
        // z seedSlicedEdges, które tylko zasilają cache bez BRoutera). Odpowiedź na „ile strzałów po co".
        java.util.Map<String, Long> byReason = cache.realCallsByReason();
        log.info("ALNS2 STRZAŁY/plan (seed, realne brouter.apply per powód): grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} inne={} | RAZEM realnych={} (misses={}; różnica = sliced-seedy bez BRoutera)",
                new Object[]{
                        byReason.getOrDefault("grow", 0L),
                        byReason.getOrDefault("ogonek-relokacja", 0L),
                        byReason.getOrDefault("ogonek-scalenie", 0L),
                        byReason.getOrDefault("dziura-otoczona", 0L),
                        byReason.getOrDefault("dziura-przy-trasie", 0L),
                        byReason.getOrDefault("pomiar", 0L),
                        byReason.getOrDefault("inne", 0L),
                        cache.realCalls(), cache.misses()});
        // DIAGNOSTYKA: top-K najdłuższych legów (hav km / real km / ile RÓŻNYCH gmin kredytują). Duże #gmin =
        // konieczny transit (OK); #gmin≈0 = pusty TSP-nawrót (kandydat do naprawy). getEdge = cache (bez BRoutera).
        final int LEG_TOPK = 15;
        List<Integer> legIdx = new ArrayList<>();
        for (int i = 0; i < route.size() - 1; i++) legIdx.add(i);
        legIdx.sort((x, y) -> Double.compare(
                velomarker.service.planning.WaypointSelector.haversineKm(route.get(y), route.get(y + 1)),
                velomarker.service.planning.WaypointSelector.haversineKm(route.get(x), route.get(x + 1))));
        StringBuilder legSb = new StringBuilder();
        for (int t = 0; t < Math.min(LEG_TOPK, legIdx.size()); t++) {
            int i = legIdx.get(t);
            double havKm = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
            EdgeCache.EdgeInfo e = getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter);
            Set<Integer> gset = new HashSet<>();
            for (double[] p : e.geometry()) {
                UnvisitedArea a = gminaIndex.findGminaForPoint(p[0], p[1]);
                if (a != null) gset.add(a.areaId());
            }
            legSb.append(String.format(java.util.Locale.ROOT, " [hav=%.0f real=%.0f gmin=%d]",
                    havKm, e.distanceKm(), gset.size()));
        }
        log.info("ALNS2 seed top-{} długich legów (hav/real km, #gmin kredytowanych):{}", LEG_TOPK, legSb);
    }

    /** Min. REALNY detour (km z EdgeCache: eIn+eOut−eDirect) kwalifikujący ślepy przejazd do resolvera.
     *  Realne km zamiast haversine — mosty/rzeki (Wisła!) dają duży realny objazd przy małym hav. */
    private static final double TAIL_MIN_DETOUR_REAL_KM = 2.0;
    /** Głębokość wejścia nowego entry-pointu za granicę gminy PO REALNYM śladzie. User proponował 100 m,
     *  ale kredyt gminy wymaga wjazdu ≥ CREDIT_DEPTH_M=200 m (adaptive, JtsAreaCoverageIndex) — 100 m
     *  = utrata kredytu. 300 m = sprawdzony margines (graź-points używają 280 m). */
    private static final double TAIL_INSIDE_M = 300.0;
    /** Min. zysk effort (km-ekwiwalent) by zaakceptować MOVE — mniejsze zyski to churn cache'u. */
    private static final double TAIL_MOVE_MIN_GAIN = 0.2;
    /** WIGGLE (v3.2): warianty pozycji wp w gminie — tylko spury z realDetour ≥ tego progu. */
    private static final double WIGGLE_MIN_DETOUR_KM = 3.0;
    /** WIGGLE: max prób per pass (każda próba = do 5 kandydatów × 2 BRouter calls). */
    private static final int WIGGLE_MAX_PER_PASS = 20;
    /** WIGGLE: min zysk effort by zaakceptować alternatywny entry-point (wyżej niż shorten — to
     *  spekulacyjna zmiana, nowe ogonki z niej tnie kolejny pass/cykl). */
    private static final double WIGGLE_MIN_GAIN = 0.5;
    /** MOVE-SLICED (v4): max odległość wierzchołka eOut od punktu cięcia na eIn, by uznać spur za
     *  out-and-back po TEJ SAMEJ drodze (wtedy skrót = cięcie geometrii, zero BRouter). */
    private static final double SLICE_SNAP_KM = 0.05;
    private static final double RETRACE_TOL_KM = 0.06; // v3.24: out-and-back — eOut wraca TĄ SAMĄ drogą co eIn
    /** STERCZENIE W BOK (v3.10): prawdziwy ogonek odstaje od linii prev→next o ~głębokość tipu;
     *  kotwica na KRĘTEJ drodze ledwo odstaje, choć proxy-detour (real−hav) ma duży. Poniżej tego
     *  progu nie pytamy i nie skracamy — pewniaki działają bez progu. */
    private static final double TAIL_MIN_LATERAL_KM = 1.0;

    /** RELOKACJA (v6): minimalny realny detour (km), by uznać jedynego-kontaktu za spur warty
     *  przestawienia na płytkie wejście. Poniżej — zostaw (legit płytki dotyk). */
    private static final double RELOC_MIN_DETOUR_KM = 2.0;

    /** STRZAŁY/plan po fazie: skumulowane realne brouter.apply per powód + Δ od poprzedniej fazy.
     *  Zwraca świeży snapshot (przekaż jako {@code prev} w kolejnym wywołaniu). Total niesiony pod
     *  kluczem "RAZEM" → Δfaza bez osobnej zmiennej. Wołaj tylko gdy {@code debugGeoJson}. */
    private Map<String, Long> logShots(String phase, Map<String, Long> prev, EdgeCache cache) {
        Map<String, Long> now = new HashMap<>(cache.realCallsByReason());
        long total = cache.realCalls(), prevTotal = prev.getOrDefault("RAZEM", 0L);
        java.util.function.BiFunction<String, Long, String> f =
                (k, n) -> n + "(Δ" + (n - prev.getOrDefault(k, 0L)) + ")";
        log.info("ALNS2 STRZAŁY/plan [{}]: grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} | RAZEM={} (Δfaza={})",
                new Object[]{phase,
                        f.apply("grow", now.getOrDefault("grow", 0L)),
                        f.apply("ogonek-relokacja", now.getOrDefault("ogonek-relokacja", 0L)),
                        f.apply("ogonek-scalenie", now.getOrDefault("ogonek-scalenie", 0L)),
                        f.apply("dziura-otoczona", now.getOrDefault("dziura-otoczona", 0L)),
                        f.apply("dziura-przy-trasie", now.getOrDefault("dziura-przy-trasie", 0L)),
                        f.apply("pomiar", now.getOrDefault("pomiar", 0L)),
                        total, total - prevTotal});
        now.put("RAZEM", total);
        return now;
    }

    /**
     * TAIL-PRUNE v6 — JTS-CLEAN (refactor v3.15): JEDEN silnik geometrii (port, kryterium kredytu).
     * Sprzątanie decyduje z AUTORYTATYWNEGO indeksu „które legi kredytują gminę" (per-leg
     * {@code visitedAreaIds}, 0 BRouter) — nie z ręcznej księgowości, która rozjeżdżała się z
     * kredytem (bug punktu 11: gmina kryta innym legiem, ale reanchor po pełnej geometrii nie
     * trafiał → spur ZOSTAWAŁ).
     *
     * <p>Per punkt (po malejącym detourze, sąsiedzi blokowani w passie):
     * <ul>
     *   <li><b>PURE-WASTE</b> (każda gmina legów punktu kryta TEŻ innym, wciąż obecnym legiem) →
     *       USUŃ BEZWARUNKOWO (scal prev→next, 1 call finalize). Bez bramki „uda się reanchor" —
     *       invariant „gmina=waypoint" pilnuje jeden ANCHOR-TRANSIT na końcu. To naprawia 11/
     *       Czosnów/Leszno/Szczawin/Płock — całą masę {@code MA-DRUGI-KONTAKT|trzyma NIC}.</li>
     *   <li><b>JEDYNY-KONTAKT za głęboko</b> (detour ≥ {@value #RELOC_MIN_DETOUR_KM} km) → PRZESTAW
     *       na płytkie wejście przy granicy ({@link #relocateShallowOwnLeg}: własna noga slice +
     *       1 call powrót, akcept tylko gdy kredytuje). → 12/13/25/Drzewica płytkie.</li>
     *   <li>inaczej zostaw (legit płytki jedyny kontakt).</li>
     * </ul>
     * Detekcja „pure-waste" jest KONSERWATYWNA (count po pass-start, malejący przy delete) — nigdy
     * nie gubi gminy. ANCHOR-TRANSIT na końcu, potem realEffort. Mutuje route+selected.
     */
    private double tailPruneJts(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                                List<double[]> anchors, List<double[]> baseline, double[] baseCum,
                                EdgeCache cache, BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                java.util.function.Function<double[], UnvisitedArea> findGminaCached,
                                List<UnvisitedArea> pool, double targetEffort, boolean allowWiggle,
                                int maxPasses, boolean apply2opt, String debugPhase) {
        long callsStart = cache.realCalls(); // v3.18: realne strzały (nie misses — te liczą sliced-seedy relokacji)
        cache.setReason("pomiar"); // v3.16: legGminas/pomiar w tailPrune = ~0 realnych; relokacja/scalenie tagowane niżej
        double effortBefore = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> visitedBefore = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);

        int deleted = 0;
        int relocated = 0;
        int relocSkipped = 0;
        int relocRestored = 0; // v3.21: relokacje COFNIĘTE (spłycenie zgubiłoby gminę = jedyne wejście)
        int pendingRerouteCount = 0; // v3.30 (Q2): ile reroute'ów loop-spurów (#111/#32) — bounded do CAP
        final int REROUTE_CAP = 50;  // twardy limit strzałów reroute/tailPrune-call (anty-eksplozja 951)
        int passes = 0;
        Map<double[], String> refusal = new java.util.IdentityHashMap<>();
        Set<double[]> stay = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); // v3.21: cofnięte „jedyne wejście" — nie ruszaj w kolejnych passach
        int round = 0;
        int rer = 0; // v3.30 (Q2): ile sliced legów przeroutowano realnie w tej rundzie (warunek pętli)
        // RUNDA 24: entry-anchor USUNIĘTY — anchor-intersects (reset wszystkich touched gmin) leci teraz OSOBNO przed
        // tailPruneJts (w COMPACT-LOOP). tailPruneJts już TYLKO tnie ogonki do skutku. Pokrycie wewnątrz cięcia trzyma
        // inline-PRZESUŃ (po deletach) + restore-on-drop.
        do { // cięcie DO SKUTKU — {passy(+inline PRZESUŃ) → reroute}, powtórz gdy reroute ujawnił wtórniaki (rer>0). cap 3 rundy.
        // Zmiana B/C: bazy do per-runda log/strzałów (gated debugGeoJson).
        int przesunRound = 0; // RUNDA 14: suma wp dostawionych przez inline-PRZESUŃ w tej rundzie (do TAIL-PRUNE v6).
        int delRoundStart = deleted, relRoundStart = relocated;
        List<String> killLog = debugGeoJson ? new ArrayList<>() : null; // RUNDA 25: anatomia USUNIĘTYCH ogonków (czemu zasadne padają)
        Set<Integer> visBeforeRound = debugGeoJson ? gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter)) : null;
        Map<String, Long> shotsRoundStart = null;
        if (debugGeoJson) {
            shotsRoundStart = new HashMap<>(cache.realCallsByReason());
            shotsRoundStart.put("RAZEM", cache.realCalls());
        }
        // „PRZED cięciem": geometria PO anchorze (Zmiana A), PRZED passami tnącymi tej rundy.
        // precut{round} → cut{round} izoluje co ucięły passy (porównaj z dropped-runda w logu v6).
        if (debugGeoJson) debugGeometry(debugPhase + "-precut" + round,
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
        boolean changed = true;
        int rp = 0; // passy tej rundy; `passes` = łączny licznik do logu
        while (changed && rp < maxPasses + 6) {
            changed = false;
            rp++;
            passes++;
            int n = route.size();
            if (n < 3) break;
            // Autorytatywny indeks per-leg (kryterium kredytu, 0 BRouter): legGminas[i] = gminy
            // kredytowane przez leg i; count[g] = w ilu legach gmina występuje.
            List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
            Map<Integer, Integer> count = new HashMap<>();
            for (int i = 0; i < n - 1; i++) {
                Set<Integer> s = gminaIndex.visitedAreaIds(
                        getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).geometry());
                legGminas.add(s);
                for (int g : s) count.merge(g, 1, Integer::sum);
            }
            boolean[] locked = new boolean[n];
            // v3.21: REAL visited na WEJŚCIU passa (0 BRouter — cache) do restore-on-drop.
            Set<Integer> passVisited = gminaIndex.visitedAreaIds(
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            // Kandydaci wg detouru DESC (real km z cache − hav). Referencje punktów (route mutuje).
            record Cand(double[] point, int idx, double detour) {}
            List<Cand> cands = new ArrayList<>();
            for (int i = 1; i < n - 1; i++) {
                double[] cur = route.get(i);
                if (isAnchor(cur, anchors)) continue;
                double[] prev = route.get(i - 1);
                double[] next = route.get(i + 1);
                EdgeCache.EdgeInfo eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter);
                EdgeCache.EdgeInfo eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter);
                double detour = eIn.distanceKm() + eOut.distanceKm()
                        - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
                cands.add(new Cand(cur, i, detour)); // v3.22: BEZ progu — głupi dip KAŻDEJ długości jest kandydatem
            }
            cands.sort((x, y) -> Double.compare(y.detour(), x.detour()));

            List<double[][]> relocPairs = new ArrayList<>();   // nogi powrotne loop-spurów (batch)
            record Reloc(double[] newWp, double[] origCur, Set<Integer> origGminy) {}
            List<Reloc> relocs = new ArrayList<>();             // v3.25: do restore-on-drop ciętych/spłyconych
            List<double[]> toDelete = new ArrayList<>();        // v3.26: loop kryte-gdzie-indziej (kutas null) → usuń
            record Del(double[] prev, double[] origCur, SeedSel sel, Set<Integer> origGminy) {}
            List<Del> dels = new ArrayList<>();                 // v3.26: do restore-on-drop (re-insert)
            for (Cand c : cands) {
                int idx = c.idx;
                if (idx <= 0 || idx >= route.size() - 1) continue;
                if (locked[idx - 1] || locked[idx] || locked[idx + 1]) continue;
                Set<Integer> gIn = legGminas.get(idx - 1);
                Set<Integer> gOut = legGminas.get(idx);
                double[] prev = route.get(idx - 1);
                double[] cur = c.point();
                // RUNDA 12: `stay` (cofnięte unikalne wejście) NIE blokuje już całkowicie — wolno mu zrobić
                // shallow-snap na WEJŚCIE (relocateShallowDeferred niżej, nie gubi gminy). Blokujemy tylko DELETE
                // i agresywne cięcie outAndBack (niżej `if (stayLocked) … continue`). Po snapie kolejny pass no-opuje.
                boolean stayLocked = stay.contains(cur);
                double[] next = route.get(idx + 1);
                UnvisitedArea g0 = findGminaCached.apply(cur);
                if (g0 == null) { refusal.put(cur, "bez-gminy"); continue; }
                EdgeCache.EdgeInfo eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter);
                EdgeCache.EdgeInfo eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter);
                // ═══════════ RUNDA 60 — REGUŁA ZAUŁKÓW (od usera), drzewo decyzyjne per wp ═══════════
                // KROK 1: czy wp to ZAUŁEK = ślepy przejazd (ślad wjeżdża do cur i ZAWRACA = retrace)?
                //   NIE (ślad PRZELOTEM przez cur) → ZOSTAW (#100). Pierwszy filtr = retrace, NIE excl/count → non-zaułki
                //   NIGDY nie usuwane (to chroni przed „znikło w chuj wp" starego covered-loop DELETE bez bramki).
                if (outAndBackDivergence(eIn, eOut) == null) { refusal.put(cur, "nie-zaułek-zostaw"); continue; }
                if (stayLocked) { refusal.put(cur, "stay-zostaw"); continue; } // restored unikalny wjazd → nie churnuj
                // KROK 2: wp JEST zaułkiem. exclusive = gminy kredytowane TYLKO nogami tego wp (count ≤ wkład).
                //   PUSTE = WSZYSTKIE kryte gdzie indziej ≥200m (MA-DRUGI-KONTAKT — count na CAŁYM śladzie, łapie DALEKI
                //   drugi wjazd #133/Radzymin którego lokalny przelot prev→next NIE widział).
                Set<Integer> exclusive = new HashSet<>();
                for (int g : gIn) if (count.getOrDefault(g, 0) <= 1 + (gOut.contains(g) ? 1 : 0)) exclusive.add(g);
                for (int g : gOut) if (!gIn.contains(g) && count.getOrDefault(g, 0) <= 1) exclusive.add(g);
                if (exclusive.isEmpty()) {
                    // 2a: ZAUŁEK ZBĘDNY (g0 kryta gdzie indziej, choćby DALEKO — #133/Głowno/#65) → USUŃ spur
                    //   (collapse prev→next). Korytarz trzyma g0; wp na ❤️ (220m korytarza) wstawi inline re-anchor po
                    //   deletach (Fix B). USUŃ tylko bo to ZAUŁEK (KROK 1 powyżej) — non-zaułki nie docierają tu.
                    if (killLog != null) killLog.add(String.format(java.util.Locale.ROOT, "#%d@%.4f,%.4f %s pow=zaułek-zbędny→usuń count=%d",
                            idx, cur[0], cur[1], g0.name(), count.getOrDefault(g0.areaId(), 0)));
                    SeedSel delSel = null;
                    for (SeedSel s : selected) if (s.point() == cur) { delSel = s; break; }
                    toDelete.add(cur);
                    Set<Integer> ogD = new HashSet<>(gIn); ogD.addAll(gOut);
                    dels.add(new Del(prev, cur, delSel, ogD));
                    for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
                    for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
                    legGminas.set(idx - 1, new HashSet<>()); // scalony leg = ∅ do końca passa (konserwatywnie)
                    legGminas.set(idx, new HashSet<>());
                    deleted++;
                    locked[idx - 1] = locked[idx] = locked[idx + 1] = true;
                    changed = true;
                    continue;
                }
                // 2b: JEDYNE wejście (excl≥1 = g0 kryta TYLKO tym wp). Ustaw wp DOKŁADNIE 220m od wjazdu na WŁASNEJ nodze:
                //   >220m → SKRÓĆ; 200–220m → POGŁĘB; już ~220m → ZOSTAW (no-op w reloc-shallow: haversine<0.15).
                //   reloc-shallow celuje firstBufferEntryPoints (220m, Fix B z R54); guard RUNDA 39 usunięty → skraca za-głębokie.
                Set<Integer> preserve = Set.of(g0.areaId());
                RelocResult rr = relocateShallowDeferred(route, selected, baseline, baseCum, cache, brouter, profile,
                        alphaKmPerMeter, gminaIndex, preserve, prev, cur, next, idx, g0, eIn, eOut,
                        pendingRerouteCount < REROUTE_CAP); // bounded reroute dla loop-spurów
                if (rr.ok()) {
                    if (killLog != null) killLog.add(String.format(java.util.Locale.ROOT, "#%d@%.4f,%.4f→%.4f,%.4f %s pow=jedyny→220 count=%d",
                            idx, cur[0], cur[1], route.get(idx)[0], route.get(idx)[1], g0.name(), count.getOrDefault(g0.areaId(), 0)));
                    relocated++; changed = true;
                    Set<Integer> ogR = new HashSet<>(gIn); ogR.addAll(gOut);
                    relocs.add(new Reloc(route.get(idx), cur, ogR));
                    for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
                    for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
                    for (int g : rr.newInCredit()) count.merge(g, 1, Integer::sum);
                    for (int g : rr.newOutCredit()) count.merge(g, 1, Integer::sum);
                    legGminas.set(idx - 1, rr.newInCredit());
                    legGminas.set(idx, rr.newOutCredit());
                    if (rr.pendingDeparture() != null) {
                        relocPairs.add(rr.pendingDeparture());
                        pendingRerouteCount++;
                        stay.add(route.get(idx));
                    }
                    continue;
                }
                // przelot nie kryje g0 + brak 220m na własnej nodze / nie-sliceowalne → ZOSTAW (jedyny wjazd, nie gub gminy).
                relocSkipped++;
                refusal.put(cur, "jedyny-zostaw");
            }
            if (!relocPairs.isEmpty()) {
                cache.setReason("ogonek-relokacja"); // nogi powrotne loop-spurów RAZEM (równoległy batch)
                prewarmPairs(relocPairs, cache, brouter, profile, alphaKmPerMeter);
                cache.setReason("pomiar");
            }
            // v3.26: APLIKUJ delete'y loop kryte-gdzie-indziej (po pętli — route.remove przesuwa indeksy).
            // locked gwarantuje że sąsiedzi usuwanych są nietknięci → prev/next intakt, mergedPair poprawny.
            if (!toDelete.isEmpty()) {
                List<double[][]> mergedPairs = new ArrayList<>();
                for (double[] d : toDelete) {
                    int di = identityIndexOf(route, d);
                    if (di > 0 && di < route.size() - 1)
                        mergedPairs.add(new double[][]{route.get(di - 1), route.get(di + 1)});
                }
                for (double[] d : toDelete) {
                    final double[] dd = d;
                    int di = identityIndexOf(route, dd);
                    if (di >= 0) { route.remove(di); selected.removeIf(s -> s.point() == dd); }
                }
                cache.setReason("ogonek-scalenie"); // scalone prev→next RAZEM (równoległy batch)
                prewarmPairs(mergedPairs, cache, brouter, profile, alphaKmPerMeter);
                cache.setReason("pomiar");
                // RUNDA 60 — ZAUŁEK-MOVE = DOKOŃCZENIE cięcia zbędnego zaułka: wp z usuniętego zaułka MA wylądować NA
                // KORYTARZU 220m od granicy g0 → „PRZESUŃ WP NA ŚLAD" (spur znika wyżej, tu jego wp ląduje na ciągłym
                // śladzie). To CIĘCIE (PROCES 2), NIE anchor: anchor (PROCES 1) stawia 220m dla KAŻDEJ gminy co cykl bez
                // logiki zaułków; TU tylko dla CIĘTYCH zaułków (delGids). firstBufferEntryPoints = wspólna GEOMETRIA
                // „pierwsze −220" (oba procesy stawiają 220m od granicy — z INNEGO powodu, ta sama miara).
                Set<Integer> delGids = new HashSet<>();
                for (Del d : dels) delGids.addAll(d.origGminy());
                List<double[]> raTrack = concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter);
                Map<Integer, double[]> hearts = gminaIndex.firstBufferEntryPoints(raTrack); // 220m korytarza per gmina, RAZ
                Set<Integer> wpInsideRa = new HashSet<>();
                for (double[] p : route) {
                    UnvisitedArea gp = gminaIndex.findCreditedGminaForPoint(p[0], p[1]);
                    if (gp != null) wpInsideRa.add(gp.areaId());
                }
                for (int vid : delGids) {
                    if (wpInsideRa.contains(vid)) continue;          // korytarz już ma kredytujący wp → nic
                    double[] heart = hearts.get(vid);                // 220m korytarza (pierwsze −220 ciągłego śladu)
                    if (heart == null) continue;                     // korytarz nie wchodzi ≥220m → restore-on-drop cofnie usunięcie
                    UnvisitedArea ea = gminaIndex.findGminaForPoint(heart[0], heart[1]);
                    if (ea == null || ea.areaId() != vid) continue;  // punkt musi leżeć w vid
                    int bestLeg = -1, bestSeg = -1; double bestSD = Double.MAX_VALUE;
                    for (int j = 0; j < route.size() - 1 && bestSD > 1e-7; j++) {
                        List<double[]> g = getEdge(route.get(j), route.get(j + 1), cache, brouter, profile, alphaKmPerMeter).geometry();
                        for (int m = 0; m < g.size() - 1; m++) {
                            double sd = pointToSegmentExactKm(heart, g.get(m), g.get(m + 1));
                            if (sd < bestSD) { bestSD = sd; bestLeg = j; bestSeg = m; if (sd <= 1e-7) break; }
                        }
                    }
                    if (bestLeg < 0 || bestSD > 0.05) continue;      // 220m-punkt nie na bieżącym śladzie
                    EdgeCache.EdgeInfo be = getEdge(route.get(bestLeg), route.get(bestLeg + 1), cache, brouter, profile, alphaKmPerMeter);
                    double[] heartPt = heart.clone();
                    seedSlicedEdgesAtPoint(cache, be, route.get(bestLeg), route.get(bestLeg + 1), bestSeg, heartPt, alphaKmPerMeter);
                    route.add(bestLeg + 1, heartPt);                 // wp z zaułka → NA KORYTARZ (slice, 0 BRouter)
                    selected.add(new SeedSel(ea, heartPt, orderKey(heartPt, baseline, baseCum), 0.0, minDistToBaselineKm(heartPt, baseline)));
                    stay.add(heartPt);
                    przesunRound++;
                }
            }
            // v3.23 RESTORE-ON-DROP CELOWANY (po IDENTITY): REALNA geometria rozstrzyga. Dla KAŻDEJ
            // spadłej gminy gDrop cofnij zmianę której PUNKT LEŻY w gDrop (waypoint NAPRAWDĘ od tej gminy)
            // — NIE pierwszą-z-brzegu dotykającą jej tranzytem (to cofało 236/Warszawa = redundantny dip
            // wracał). Fallback (gmina tranzytowa bez własnego wp): jakakolwiek zmiana dotykająca gDrop.
            // 0 BRouter (edges w cache). Gwarancja: jedyne realne wejście wraca, redundantne dipy zostają.
            if (!relocs.isEmpty() || !dels.isEmpty()) {
                Set<Integer> realVisited = gminaIndex.visitedAreaIds(
                        concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
                Set<Integer> droppedPass = new HashSet<>(passVisited);
                droppedPass.removeAll(realVisited);
                Set<Object> doneR = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                boolean restoredAny = true;
                while (!droppedPass.isEmpty() && restoredAny) {
                    restoredAny = false;
                    for (int gDrop : new HashSet<>(droppedPass)) {
                        Reloc rPick = null;
                        for (Reloc r : relocs) { if (doneR.contains(r)) continue;   // 1. RELOC: punkt LEŻY w gDrop
                            UnvisitedArea ga = gminaIndex.findGminaForPoint(r.origCur()[0], r.origCur()[1]);
                            if (ga != null && ga.areaId() == gDrop) { rPick = r; break; } }
                        if (rPick == null) for (Reloc r : relocs) {                  // 2. RELOC fallback: tranzyt
                            if (!doneR.contains(r) && r.origGminy().contains(gDrop)) { rPick = r; break; } }
                        if (rPick != null) {
                            int ri = identityIndexOf(route, rPick.newWp());
                            if (ri >= 0) { route.set(ri, rPick.origCur()); swapEntryPoint(selected, rPick.newWp(), rPick.origCur(), baseline, baseCum);
                                stay.add(rPick.origCur()); relocated--; relocRestored++; restoredAny = true; }
                            doneR.add(rPick);
                            continue;
                        }
                        // v3.26: DEL → RE-INSERT origCur po prev (krawędzie prev→origCur/origCur→next w cache
                        // od początku passa = 0 BRouter). locked gwarantuje prev intakt → identityIndexOf trafia.
                        Del dPick = null;
                        for (Del d : dels) { if (doneR.contains(d)) continue;        // 3. DEL: punkt LEŻY w gDrop
                            UnvisitedArea ga = gminaIndex.findGminaForPoint(d.origCur()[0], d.origCur()[1]);
                            if (ga != null && ga.areaId() == gDrop) { dPick = d; break; } }
                        if (dPick == null) for (Del d : dels) {                       // 4. DEL fallback: tranzyt
                            if (!doneR.contains(d) && d.origGminy().contains(gDrop)) { dPick = d; break; } }
                        if (dPick != null) {
                            int pi = identityIndexOf(route, dPick.prev());
                            if (pi >= 0) { route.add(pi + 1, dPick.origCur());
                                if (dPick.sel() != null) selected.add(dPick.sel());
                                stay.add(dPick.origCur()); deleted--; relocRestored++; restoredAny = true; }
                            doneR.add(dPick);
                        }
                    }
                    if (restoredAny) {
                        realVisited = gminaIndex.visitedAreaIds(
                                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
                        droppedPass = new HashSet<>(passVisited);
                        droppedPass.removeAll(realVisited);
                    }
                }
            }
        }

        // RUNDA 11: C (anchor przed sweepem) + DELETE-SWEEP usunięte — unifikacja USUŃ→PRZESUŃ (anchor inline po
        // deletach w passie) trzyma pokrycie na bieżąco, więc nie ma „chwilowych tranzytów" do dosprzątania.
        // v3.30 (Q2): „strzel ponownie o cały ślad" — przeroutuj sliced legi REALNIE; wtórniaki na
        // prawdziwej geometrii ujawniają się i są docinane w kolejnej rundzie. Mało strzałów, zbiega.
        rer = rerouteApproximateLegs(route, cache, brouter, profile, alphaKmPerMeter);
        // Zmiana 2: geometria PO każdej rundzie do-skutku (też wtórnej) — cut0 = cięcie pierwotne,
        // cut1+ = wtórne (po scaleniach sweepa + realnym re-route, ujawnione dopiero przez broutera).
        if (debugGeoJson) {
            debugGeometry(debugPhase + "-cut" + round,
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            // Zmiana B (Runda 2/3): pełny v6 PO KAŻDEJ rundzie cięcia (sumy + effort) + delty rundy +
            // NAZWY gmin utraconych w tej rundzie (diagnoza „gdzie ginie Raszyn").
            int cutThisRound = (deleted - delRoundStart) + (relocated - relRoundStart);
            double roundEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
            Set<Integer> visAfterRound = gminaIndex.visitedAreaIds(
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            Set<Integer> droppedCum = new HashSet<>(visitedBefore); droppedCum.removeAll(visAfterRound);
            List<String> droppedRoundNames = new ArrayList<>();
            for (int g : visBeforeRound) if (!visAfterRound.contains(g)) {
                UnvisitedArea ga = idToArea.get(g);
                droppedRoundNames.add(ga != null ? ga.name() : ("id" + g));
            }
            boolean willContinue = rer > 0 && (round + 1) < 3;
            log.info("ALNS2 TAIL-PRUNE v6 [{}-cut{}]: deleted={}, relocated={}, restored={}, reloc-skipped={}, passes={}, dropped(cum)={}, calls={}, effort {}→{} ({}%→{}%) | runda: del+{}, reloc+{}, przesuń+{}, reroute={}, cut-razem={}, dropped-runda={} {} → {}",
                    new Object[]{debugPhase, round, deleted, relocated, relocRestored, relocSkipped, passes,
                            droppedCum.size(), cache.realCalls() - callsStart,
                            Math.round(effortBefore), Math.round(roundEffort),
                            Math.round(effortBefore * 100.0 / targetEffort),
                            Math.round(roundEffort * 100.0 / targetEffort),
                            deleted - delRoundStart, relocated - relRoundStart, przesunRound, rer, cutThisRound,
                            droppedRoundNames.size(), droppedRoundNames,
                            willContinue ? "kolejna runda" : "KONIEC pętli"});
            // RUNDA 25: anatomia USUNIĘTYCH ogonków tej rundy (co/czemu cięcie usunęło — diagnoza utraty zasadnych wjazdów).
            log.info("ALNS2 USUNIĘTE-OGONKI [{}-cut{}]: {} pozycji (del/reloc): {}",
                    new Object[]{debugPhase, round, killLog == null ? 0 : killLog.size(), killLog});
            // Zmiana C: STRZAŁY zużyte w tej rundzie cięcia (Δ od startu rundy).
            logShots(debugPhase + "-cut" + round, shotsRoundStart, cache);
            // RUNDA 11: SPUR-ANATOMIA po KAŻDEJ rundzie cięcia (nie tylko na końcu).
            debugSpurAnatomyJts(route, anchors, cache, brouter, profile, alphaKmPerMeter, gminaIndex,
                    findGminaCached, idToArea, refusal, debugPhase + "-cut" + round);
        }
        round++;
        } while (rer > 0 && round < 3); // do-skutku: re-cut wtórniaki ujawnione realnym re-route

        // v3.31 FIX C: 2opt po cięciu W TRAKCIE budowy (apply2opt=true) — krótszy ślad, więcej budżetu.
        // Finalny clean (apply2opt=false, w paśmie) NIE 2opt-uje, bo 2opt potrafi GENEROWAĆ spury →
        // gładki finalny ślad. Każda gmina ma waypoint (do-skutku skończyło anchorTransit) → 2opt tylko
        // przestawia (nie gubi pokrycia); po 2opt anchorTransit dokłada ewentualne nowe tranzyty.
        if (apply2opt) {
            // RUNDA 40 (B): loguj finalny 2-opt cięcia (to ON daje różnicę `…-cutN` → `…-real`). effort DOKŁADNY przed→po.
            double e2Before = debugGeoJson ? routeEffortAccurate(route, cache, brouter, profile, alphaKmPerMeter) : 0;
            Alns2LocalSearch.twoOpt(route);
            if (debugGeoJson) {
                double e2After = routeEffortAccurate(route, cache, brouter, profile, alphaKmPerMeter);
                log.info("ALNS2 2-OPT [{}]: effort {}→{} (Δ{}), wps={}", new Object[]{debugPhase,
                        Math.round(e2Before), Math.round(e2After), Math.round(e2After - e2Before), route.size()});
            }
        }

        // RUNDA 50: egzekwuj 1 wp/gmina — usuń płytkie duplikaty zostawione przez inline-re-kotwicę (#167/#168 nałożone).
        dedupeOneWpPerGmina(route, selected, anchors, cache, brouter, profile, alphaKmPerMeter, gminaIndex);

        double realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Set<Integer> dropped = new HashSet<>(visitedBefore);
        dropped.removeAll(visitedAfter);
        log.info("ALNS2 TAIL-PRUNE v6 (JTS-clean): deleted={}, relocated={}, restored={}, reloc-skipped={}, passes={}, dropped={}, calls={}, effort {}→{} ({}%→{}%)",
                new Object[]{deleted, relocated, relocRestored, relocSkipped, passes, dropped.size(),
                        cache.realCalls() - callsStart, Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort)});
        if (debugGeoJson) {
            debugSpurAnatomyJts(route, anchors, cache, brouter, profile, alphaKmPerMeter, gminaIndex,
                    findGminaCached, idToArea, refusal, debugPhase);
            debugGeometry(debugPhase,
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
        }
        return realEffort;
    }

    /**
     * RUNDA 69 — PODWÓJNE CIĘCIE „dla pewności": cut → 2opt → reroute → cut. Po pierwszym cięciu 2-opt przestawia
     * kolejność wp (haversine), a drugie cięcie {@code tailPruneJts2(phase+"-recut")} liczy legGminas przez getEdge
     * NOWYCH par = nowy wariant trasy z BRoutera, i tnie wtórne ogonki. Oba cięcia mają RÓŻNE phase → każdy emituje
     * własny komplet logów (TAIL-PRUNE v6 / USUNIĘTE-OGONKI / SPUR-ANATOMIA) + debugGeometry. Zwraca effort po cut2.
     */
    private double doubleCut(List<double[]> route, List<SeedSel> selected, List<double[]> anchors,
                             List<double[]> baseline, double[] baseCum, EdgeCache cache,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
                             double alphaKmPerMeter, GminaIndex gminaIndex,
                             java.util.function.Function<double[], UnvisitedArea> findGminaCached,
                             List<UnvisitedArea> pool, double targetEffort, int maxPasses, String phase) {
        tailPruneJts2(route, selected, anchors, baseline, baseCum, cache, brouter, profile,
                alphaKmPerMeter, gminaIndex, findGminaCached, pool, targetEffort, maxPasses, phase);
        twoOptLogged(route, phase + "-recut2opt");
        return tailPruneJts2(route, selected, anchors, baseline, baseCum, cache, brouter, profile,
                alphaKmPerMeter, gminaIndex, findGminaCached, pool, targetEffort, maxPasses, phase + "-recut");
    }

    /**
     * RUNDA 63 — CIĘCIE OGONKÓW v2 (czyste, wg reguły zaułków). PROCES 2, leci PO anchorResetTouched (PROCES 1).
     * Każda gmina ma już 1 wp z anchora → cięcie TYLKO PRZESUWA wp w obrębie JEGO gminy (route.set). Reguła per wp:
     *   1. NIE zaułek (przelot, ślad PRZECHODZI: outAndBackDivergence==null) → ZOSTAW.
     *   2a. zaułek + g0 kryta ≥220m GDZIE INDZIEJ (exclusive puste) → PRZESTAW na przelot prev→next (220m W g0).
     *   2b. zaułek jedyny (exclusive≥1) → SKRÓĆ/POGŁĘB do 220m na WŁASNEJ nodze (relocateShallowDeferred).
     * Zero delete/insert/re-anchor/restore/dedupe (anchor trzyma 1 wp/gmina; cięcie tylko repozycjonuje w gminie).
     * Logi (TAIL-PRUNE v6 / USUNIĘTE-OGONKI / STRZAŁY / SPUR-ANATOMIA) jak w v1.
     */
    private double tailPruneJts2(List<double[]> route, List<SeedSel> selected, List<double[]> anchors,
                                 List<double[]> baseline, double[] baseCum, EdgeCache cache,
                                 BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
                                 double alphaKmPerMeter, GminaIndex gminaIndex,
                                 java.util.function.Function<double[], UnvisitedArea> findGminaCached,
                                 List<UnvisitedArea> pool, double targetEffort, int maxPasses, String debugPhase) {
        long callsStart = cache.realCalls();
        cache.setReason("pomiar");
        double effortBefore = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> visitedBefore = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);

        int relocated = 0, relocSkipped = 0, passes = 0, pendingRerouteCount = 0;
        final int REROUTE_CAP = 50;
        Map<double[], String> refusal = new java.util.IdentityHashMap<>();
        Set<double[]> stay = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int round = 0, rer = 0;
        do {
            int relRoundStart = relocated;
            List<String> killLog = debugGeoJson ? new ArrayList<>() : null;
            Set<Integer> visBeforeRound = debugGeoJson ? gminaIndex.visitedAreaIds(
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter)) : null;
            Map<String, Long> shotsRoundStart = null;
            if (debugGeoJson) {
                shotsRoundStart = new HashMap<>(cache.realCallsByReason());
                shotsRoundStart.put("RAZEM", cache.realCalls());
                debugGeometry(debugPhase + "-precut" + round,
                        concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                        routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
            }
            boolean changed = true;
            int rp = 0;
            while (changed && rp < maxPasses + 6) {
                changed = false; rp++; passes++;
                int n = route.size();
                if (n < 3) break;
                // RUNDA 66: per-leg index na −220 (deeplyVisitedAreaIds, 0 BRouter): legGminas[i] = gminy w które noga i
                // wchodzi GŁĘBOKO ≥220m (PRZELOT, nie muśnięcie); count[g] = w ilu nogach. Decyzja cięcia: czy g0 ma
                // przelot GDZIE INDZIEJ ≥220m (spójne z re-anchorem, który też −220 → cel zawsze istnieje gdy tniemy).
                List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
                Map<Integer, Integer> count = new HashMap<>();
                for (int i = 0; i < n - 1; i++) {
                    Set<Integer> s = gminaIndex.deeplyVisitedAreaIds(
                            getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).geometry());
                    legGminas.add(s);
                    for (int g : s) count.merge(g, 1, Integer::sum);
                }
                record Cand(double[] point, int idx, double detour) {}
                List<Cand> cands = new ArrayList<>();
                for (int i = 1; i < n - 1; i++) {
                    double[] cur = route.get(i);
                    if (isAnchor(cur, anchors)) continue;
                    double det = getEdge(route.get(i - 1), cur, cache, brouter, profile, alphaKmPerMeter).distanceKm()
                            + getEdge(cur, route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).distanceKm()
                            - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                    cands.add(new Cand(cur, i, det));
                }
                cands.sort((x, y) -> Double.compare(y.detour(), x.detour()));
                List<double[][]> relocPairs = new ArrayList<>();
                List<double[]> toDelete = new ArrayList<>();   // RUNDA 65: zbędne zaułki → USUŃ + re-anchor na przelocie (batch po pętli)
                Set<Integer> delGids = new HashSet<>();
                boolean[] locked = new boolean[n];             // chroni sąsiadów usuwanego spuru (poprawny merge prev→next)
                for (Cand c : cands) {
                    int idx = c.idx();
                    if (idx <= 0 || idx >= route.size() - 1) continue;
                    if (locked[idx - 1] || locked[idx] || locked[idx + 1]) continue;
                    double[] cur = c.point();
                    if (stay.contains(cur)) { refusal.put(cur, "stay-zostaw"); continue; }
                    double[] prev = route.get(idx - 1), next = route.get(idx + 1);
                    UnvisitedArea g0 = findGminaCached.apply(cur);
                    if (g0 == null) { refusal.put(cur, "bez-gminy"); continue; }
                    Set<Integer> gIn = legGminas.get(idx - 1), gOut = legGminas.get(idx);
                    EdgeCache.EdgeInfo eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter);
                    EdgeCache.EdgeInfo eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter);
                    // ───────── REGUŁA 1: NIE zaułek (przelot — ślad PRZECHODZI przez cur) → ZOSTAW ─────────
                    if (outAndBackDivergence(eIn, eOut) == null) { refusal.put(cur, "przelot-zostaw"); continue; }
                    // ───────── REGUŁA 2: ZAUŁEK (ślepy przejazd, ślad ZAWRACA) ─────────
                    // czy g0 ma PRZELOT ≥220m na INNEJ nodze niż ten wp? count/legGminas na −220 → wkład tego wp odejmujemy.
                    // wp-na-granicy −220 wpływa TYLKO na własny wkład (nieszkodliwie); cel cięcia = głęboka noga gdzie indziej.
                    int contribDeep = (gIn.contains(g0.areaId()) ? 1 : 0) + (gOut.contains(g0.areaId()) ? 1 : 0);
                    boolean deepElsewhere = count.getOrDefault(g0.areaId(), 0) > contribDeep;
                    if (deepElsewhere) {
                        // 2a: ZBĘDNY zaułek — g0 ma PRZELOT ≥220m GDZIE INDZIEJ (choćby na DALEKIEJ nodze). USUŃ spur
                        //   (collapse prev→next); wp wstawimy na PRZELOCIE w g0 po pętli (re-anchor, 1 wp/gmina). Jedna
                        //   mechanika — re-anchor znajdzie nogę-przelot geometrią (indeks nogi nieistotny: #4-5 czy #204-205).
                        if (killLog != null) killLog.add(String.format(java.util.Locale.ROOT, "#%d %s | DRUGI-KONTAKT | zbędny→usuń+przelot", idx, g0.name()));
                        toDelete.add(cur);
                        delGids.add(g0.areaId());
                        for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
                        for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
                        legGminas.set(idx - 1, new HashSet<>());
                        legGminas.set(idx, new HashSet<>());
                        locked[idx - 1] = locked[idx] = locked[idx + 1] = true;
                        relocated++; changed = true; continue;
                    }
                    // 2b: JEDYNY wjazd → SKRÓĆ/POGŁĘB do 220m na WŁASNEJ nodze (route.set wewnątrz relocateShallowDeferred).
                    RelocResult rr = relocateShallowDeferred(route, selected, baseline, baseCum, cache, brouter, profile,
                            alphaKmPerMeter, gminaIndex, Set.of(g0.areaId()), prev, cur, next, idx, g0, eIn, eOut,
                            pendingRerouteCount < REROUTE_CAP);
                    if (rr.ok()) {
                        if (killLog != null) { // RUNDA 67: flaga — wynik MUSI być głęboki (kredytuje −200)
                            UnvisitedArea ccc = gminaIndex.findCreditedGminaForPoint(route.get(idx)[0], route.get(idx)[1]);
                            killLog.add(String.format(java.util.Locale.ROOT, "#%d %s | JEDYNY | →220m %s", idx, g0.name(),
                                    ccc != null && ccc.areaId() == g0.areaId() ? "(głęboki)" : "(PŁYTKI!)"));
                        }
                        relocated++; changed = true;
                        for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
                        for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
                        Set<Integer> nInDeep = gminaIndex.deeplyVisitedAreaIds(getEdge(prev, route.get(idx), cache, brouter, profile, alphaKmPerMeter).geometry());
                        Set<Integer> nOutDeep = gminaIndex.deeplyVisitedAreaIds(getEdge(route.get(idx), next, cache, brouter, profile, alphaKmPerMeter).geometry());
                        for (int g : nInDeep) count.merge(g, 1, Integer::sum);
                        for (int g : nOutDeep) count.merge(g, 1, Integer::sum);
                        legGminas.set(idx - 1, nInDeep); legGminas.set(idx, nOutDeep);
                        if (rr.pendingDeparture() != null) { relocPairs.add(rr.pendingDeparture()); pendingRerouteCount++; stay.add(route.get(idx)); }
                        continue;
                    }
                    refusal.put(cur, "jedyny-zostaw"); relocSkipped++;
                }
                if (!relocPairs.isEmpty()) {
                    cache.setReason("ogonek-relokacja");
                    prewarmPairs(relocPairs, cache, brouter, profile, alphaKmPerMeter); // nogi powrotne loop-spurów (batch)
                    cache.setReason("pomiar");
                }
                // RUNDA 65: USUŃ zbędne zaułki (collapse prev→next) + WSTAW wp na PRZELOCIE ≥220m w każdej usuniętej
                // gminie (re-anchor). Jedna mechanika dla przelotu lokalnego i dalekiego — nogę znajdujemy geometrią.
                if (!toDelete.isEmpty()) {
                    List<double[][]> mergedPairs = new ArrayList<>();
                    for (double[] d : toDelete) {
                        int di = identityIndexOf(route, d);
                        if (di > 0 && di < route.size() - 1) mergedPairs.add(new double[][]{route.get(di - 1), route.get(di + 1)});
                    }
                    for (double[] d : toDelete) {
                        final double[] dd = d;
                        int di = identityIndexOf(route, dd);
                        if (di >= 0) { route.remove(di); selected.removeIf(s -> s.point() == dd); }
                    }
                    cache.setReason("ogonek-scalenie");
                    prewarmPairs(mergedPairs, cache, brouter, profile, alphaKmPerMeter); // scalone prev→next (batch BRouter)
                    cache.setReason("pomiar");
                    List<double[]> raTrack = concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter);
                    Map<Integer, double[]> hearts = gminaIndex.firstBufferEntryPoints(raTrack); // gmina → pierwsze −220 przelotu, RAZ
                    for (int vid : delGids) {
                        boolean hasWp = false;                          // TWARDA bramka 1 wp/gmina (zero #23)
                        for (double[] p : route) {
                            if (isAnchor(p, anchors)) continue;
                            UnvisitedArea gp = gminaIndex.findGminaForPoint(p[0], p[1]);
                            if (gp != null && gp.areaId() == vid) { hasWp = true; break; }
                        }
                        if (hasWp) continue;
                        double[] heart = hearts.get(vid);
                        if (heart == null) continue;                    // przelot nie wchodzi ≥220m (kryte 200-220m) → anchor nast. cyklu
                        UnvisitedArea ea = gminaIndex.findGminaForPoint(heart[0], heart[1]);
                        if (ea == null || ea.areaId() != vid) continue;
                        int bestLeg = -1, bestSeg = -1; double bestSD = Double.MAX_VALUE;
                        for (int j = 0; j < route.size() - 1 && bestSD > 1e-7; j++) {
                            List<double[]> g = getEdge(route.get(j), route.get(j + 1), cache, brouter, profile, alphaKmPerMeter).geometry();
                            for (int m = 0; m < g.size() - 1; m++) {
                                double sd = pointToSegmentExactKm(heart, g.get(m), g.get(m + 1));
                                if (sd < bestSD) { bestSD = sd; bestLeg = j; bestSeg = m; if (sd <= 1e-7) break; }
                            }
                        }
                        if (bestLeg < 0 || bestSD > 0.05) continue;
                        EdgeCache.EdgeInfo be = getEdge(route.get(bestLeg), route.get(bestLeg + 1), cache, brouter, profile, alphaKmPerMeter);
                        double[] hp = heart.clone();
                        seedSlicedEdgesAtPoint(cache, be, route.get(bestLeg), route.get(bestLeg + 1), bestSeg, hp, alphaKmPerMeter);
                        route.add(bestLeg + 1, hp);                     // wp zaułka → NA PRZELOT (slice, 0 BRouter)
                        selected.add(new SeedSel(ea, hp, orderKey(hp, baseline, baseCum), 0.0, minDistToBaselineKm(hp, baseline)));
                        stay.add(hp);
                    }
                }
            }
            rer = rerouteApproximateLegs(route, cache, brouter, profile, alphaKmPerMeter); // realny re-route sliced legów → ujawnia wtórniaki
            if (debugGeoJson) {
                debugGeometry(debugPhase + "-cut" + round,
                        concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                        routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
                double roundEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                Set<Integer> visAfterRound = gminaIndex.visitedAreaIds(
                        concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
                List<String> droppedRoundNames = new ArrayList<>();
                for (int g : visBeforeRound) if (!visAfterRound.contains(g)) {
                    UnvisitedArea ga = idToArea.get(g);
                    droppedRoundNames.add(ga != null ? ga.name() : ("id" + g));
                }
                boolean willContinue = rer > 0 && (round + 1) < 3;
                log.info("ALNS2 TAIL-PRUNE v6 [{}-cut{}]: relocated={}, reloc-skipped={}, passes={}, calls={}, effort {}→{} ({}%→{}%) | runda: reloc+{}, reroute={}, dropped-runda={} {} → {}",
                        new Object[]{debugPhase, round, relocated, relocSkipped, passes, cache.realCalls() - callsStart,
                                Math.round(effortBefore), Math.round(roundEffort),
                                Math.round(effortBefore * 100.0 / targetEffort), Math.round(roundEffort * 100.0 / targetEffort),
                                relocated - relRoundStart, rer, droppedRoundNames.size(), droppedRoundNames,
                                willContinue ? "kolejna runda" : "KONIEC pętli"});
                log.info("ALNS2 USUNIĘTE-OGONKI [{}-cut{}]: {} pozycji: {}",
                        new Object[]{debugPhase, round, killLog == null ? 0 : killLog.size(), killLog});
                logShots(debugPhase + "-cut" + round, shotsRoundStart, cache);
                debugSpurAnatomyJts(route, anchors, cache, brouter, profile, alphaKmPerMeter, gminaIndex,
                        findGminaCached, idToArea, refusal, debugPhase + "-cut" + round);
            }
            round++;
        } while (rer > 0 && round < 3);

        double realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Set<Integer> dropped = new HashSet<>(visitedBefore); dropped.removeAll(visitedAfter);
        log.info("ALNS2 TAIL-PRUNE v6 (JTS-clean v2): relocated={}, reloc-skipped={}, passes={}, dropped={}, calls={}, effort {}→{} ({}%→{}%)",
                new Object[]{relocated, relocSkipped, passes, dropped.size(), cache.realCalls() - callsStart,
                        Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(realEffort * 100.0 / targetEffort)});
        if (debugGeoJson) {
            debugSpurAnatomyJts(route, anchors, cache, brouter, profile, alphaKmPerMeter, gminaIndex,
                    findGminaCached, idToArea, refusal, debugPhase);
            debugGeometry(debugPhase,
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
        }
        return realEffort;
    }

    /** RUNDA 63: gminy kredytowane TYLKO nogami tego wp (count ≤ wkład). PUSTE = wszystkie kryte ≥200m GDZIE INDZIEJ. */
    private static Set<Integer> exclusiveGminy(Set<Integer> gIn, Set<Integer> gOut, Map<Integer, Integer> count) {
        Set<Integer> exclusive = new HashSet<>();
        for (int g : gIn) if (count.getOrDefault(g, 0) <= 1 + (gOut.contains(g) ? 1 : 0)) exclusive.add(g);
        for (int g : gOut) if (!gIn.contains(g) && count.getOrDefault(g, 0) <= 1) exclusive.add(g);
        return exclusive;
    }

    /* RUNDA 11: deleteSweepCoveredElse USUNIĘTE — unifikacja USUŃ→PRZESUŃ (anchor inline po deletach w passie)
       trzyma pokrycie na bieżąco, więc nie ma „chwilowych tranzytów" do dosprzątania osobnym sweepem. */

    /** ANATOMIA spurów v6: garby ≥1 km z autorytatywnego indeksu JTS — sort po garbie DESC, cap 25.
     *  RUNDA 11: {@code phase} w logu — wołane po KAŻDEJ rundzie cięcia (cut0/1/2) + raz na końcu. */
    private void debugSpurAnatomyJts(List<double[]> route, List<double[]> anchors, EdgeCache cache,
                                     BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
                                     double alphaKmPerMeter, GminaIndex gminaIndex,
                                     java.util.function.Function<double[], UnvisitedArea> findGminaCached,
                                     Map<Integer, UnvisitedArea> idToArea, Map<double[], String> refusal, String phase) {
        int n = route.size();
        if (n < 3) return;
        List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
        Map<Integer, Integer> count = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            Set<Integer> s = gminaIndex.visitedAreaIds(
                    getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).geometry());
            legGminas.add(s);
            for (int g : s) count.merge(g, 1, Integer::sum);
        }
        // v3.16 B3: które gminy są wjeżdżane z DWÓCH stron przez RÓŻNE spury (detour≥1km) → PODWÓJNY-WJAZD
        // (#149/150/151: jedna gmina, dwa wjazdy — wystarczy jeden). Mapa gmina → indeksy spur-waypointów.
        Map<Integer, Set<Integer>> gminaToSpurWps = new HashMap<>();
        for (int i = 1; i < n - 1; i++) {
            if (isAnchor(route.get(i), anchors)) continue;
            double det = getEdge(route.get(i - 1), route.get(i), cache, brouter, profile, alphaKmPerMeter).distanceKm()
                    + getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
            if (det < 1.0) continue;
            Set<Integer> u = new HashSet<>(legGminas.get(i - 1)); u.addAll(legGminas.get(i));
            for (int gid : u) gminaToSpurWps.computeIfAbsent(gid, k -> new HashSet<>()).add(i);
        }
        record Kept(int idx, double[] pt, double detour, String gmina, String own, String refus, String blocker, String dbl, String cas) {}
        List<Kept> kept = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            double[] cur = route.get(i);
            if (isAnchor(cur, anchors)) continue;
            double[] prev = route.get(i - 1);
            double[] next = route.get(i + 1);
            EdgeCache.EdgeInfo eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter);
            EdgeCache.EdgeInfo eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter);
            double detour = eIn.distanceKm() + eOut.distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
            if (detour < 1.0) continue;
            Set<Integer> gIn = legGminas.get(i - 1);
            Set<Integer> gOut = legGminas.get(i);
            UnvisitedArea g = findGminaCached.apply(cur);
            boolean covered = false;
            if (g != null) {
                int contrib = (gIn.contains(g.areaId()) ? 1 : 0) + (gOut.contains(g.areaId()) ? 1 : 0);
                covered = count.getOrDefault(g.areaId(), 0) > contrib;
            }
            // v3.26 DIAGNOZA per spur (te same liczby co decyzja w tailPruneJts cands loop): exclusive,
            // kutas (out-and-back), inKw/outKw (najpłytszy wierzchołek kredytujący preserve / długość nogi).
            // PRZYPADEK: covered-loop (excl puste — usuwany w v3.26), deep-far (prsv=1, kw przy KOŃCU nogi),
            // multi (prsv≥2 / kw=-1, gminy rozjechane = ZOSTAW), kutas (powinien być ucięty).
            Set<Integer> exclusive = new HashSet<>();
            for (int gg : gIn) if (count.getOrDefault(gg, 0) <= 1 + (gOut.contains(gg) ? 1 : 0)) exclusive.add(gg);
            for (int gg : gOut) if (!gIn.contains(gg) && count.getOrDefault(gg, 0) <= 1) exclusive.add(gg);
            Set<Integer> preserve = exclusive.isEmpty()
                    ? (g != null ? Set.of(g.areaId()) : Set.<Integer>of()) : exclusive;
            boolean kutas = outAndBackDivergence(eIn, eOut) != null;
            int inKw = shallowestCoveringVertex(eIn.geometry(), preserve, gminaIndex);
            List<double[]> revOut = new ArrayList<>(eOut.geometry());
            java.util.Collections.reverse(revOut);
            int outKw = shallowestCoveringVertex(revOut, preserve, gminaIndex);
            int inN = eIn.geometry().size(), outN = eOut.geometry().size();
            // v3.27 FIX C: deep-far PRZED kutas (uczciwe etykiety). Stare Babice (inKw=361/363, refuse)
            // miał „kutas(tnij)" choć nic nie tnie — bo kutas=true, ale D nie kredytuje gminy z czubka.
            String przyp;
            if (exclusive.isEmpty()) przyp = "covered-loop(usuń)";
            else if (preserve.size() >= 2 || inKw < 0 || outKw < 0) przyp = "multi(zostaw)";
            else if ((inN > 3 && inKw >= inN - 3) || (outN > 3 && outKw >= outN - 3)) przyp = "deep-far";
            else if (kutas) przyp = "kutas(tnij)";
            else przyp = "krótko?";
            String cas = String.format(java.util.Locale.ROOT, "%s excl=%d cov=%b kutas=%b inKw=%d/%d outKw=%d/%d",
                    przyp, exclusive.size(), covered, kutas, inKw, inN, outKw, outN);
            // BLOKER: gminy legów punktu trzymane NA WYŁĄCZNOŚĆ (count−contrib<1) — to one blokują delete
            // (anatomia v3.15 pokazywała tylko headline-gminę → „głęboka-jedyna" nie mówiło CZEMU).
            Set<Integer> union = new HashSet<>(gIn); union.addAll(gOut);
            List<String> blk = new ArrayList<>();
            String dbl = "—";
            for (int gid : union) {
                int contrib = (gIn.contains(gid) ? 1 : 0) + (gOut.contains(gid) ? 1 : 0);
                if (count.getOrDefault(gid, 0) - contrib < 1) {
                    UnvisitedArea ba = idToArea.get(gid);
                    blk.add(ba != null ? ba.name() : ("id" + gid));
                }
                Set<Integer> wps = gminaToSpurWps.get(gid);
                if ("—".equals(dbl) && wps != null && wps.size() >= 2) {
                    UnvisitedArea da = idToArea.get(gid);
                    dbl = "PODWÓJNY-WJAZD(" + (da != null ? da.name() : ("id" + gid)) + ")";
                }
            }
            kept.add(new Kept(i, cur, detour, g != null ? g.name() : "?",
                    covered ? "MA-DRUGI-KONTAKT(!)" : "JEDYNY-KONTAKT", refusal.getOrDefault(cur, "-"),
                    blk.isEmpty() ? "—" : String.join("+", blk), dbl, cas));
        }
        kept.sort((x, y) -> Double.compare(y.detour(), x.detour()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Kept k : kept) {
            if (shown++ >= 60) break; // v3.26: cap 25→60 — pokaż też mniejsze spury (102/114/99/14/17/225)
            sb.append(String.format(java.util.Locale.ROOT, " [#%d %s | %s | %s]",
                    k.idx(), k.gmina(), k.own(), k.refus())); // RUNDA 65: tylko gmina + JEDYNY/DRUGI-KONTAKT + akcja
        }
        // RUNDA 14: loguj ZAWSZE (też 0 garbów) — żeby było widać że anatomia przebiegła po każdym cięciu.
        if (shown > 0) log.info("ALNS2 SPUR-ANATOMIA v6 [{}] ({} garbów ≥1km, top {}):{}",
                new Object[]{phase, kept.size(), Math.min(shown, 60), sb});
        else log.info("ALNS2 SPUR-ANATOMIA v6 [{}]: 0 garbów ≥1km (brak zaułków)", phase);
    }

    /**
     * TAIL-PRUNE v5 — EXACT resolver ślepych przejazdów (decyzja usera 2026-06-12 po 3 runach:
     * zgadywanie cięciwą + naprawy po fakcie przegrało — karuzela kotwic i pat „muskanej gminki").
     *
     * <p>Kandydaci po PROXY-detourze, potem darmowe filtry (pewniaki branch-a, deleteOnly,
     * sterczenie w bok ≥ {@value #TAIL_MIN_LATERAL_KM} km). Dla pozostałych PODEJRZANYCH:
     * JEDNO dokładne pytanie o prostą drogę prev→next (kotwice po splitach: cache-hit, za darmo).
     * Po odpowiedzi decyzja OSTATECZNA:
     * <ul>
     *   <li>{@code lost} (exclusive − cross(eDir)) PUSTE → DELETE; gmina punktu: RELOCATE-ON-PATH
     *       na eDir (slice) / REANCHOR na innym legu — uchwyt nigdy nie ginie.</li>
     *   <li>{@code lost} = muskane gminki → PRZEJMIJ-I-PRZESTAW (przykład usera Koluszki/Różyca):
     *       punkt przeprowadza się do gminki w miejsce najbliższe prostej drodze; akcept tylko gdy
     *       nowe legi kryją WSZYSTKO co stary spur trzymał i gain > próg (2 calle/próba, max 2).</li>
     *   <li>{@code lost} = tylko własna gmina → MOVE-shorten (out-and-back: nożyczki 0 calli;
     *       loop-spur: dojazdowa noga z pamięci + 1 call). WIGGLE bez zmian (tylko cykl 0).</li>
     * </ul>
     * FINALIZE po passie: batch BRouter tylko dla scalonych par z branch-a. Sweep (passy >
     * maxPasses): same pewniaki, aż czysto. BEZ reorderu w środku; zero napraw po fakcie.
     * Mutuje route+selected. Zwraca realEffort.
     */
    private double tailPrune(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                             List<double[]> anchors, List<double[]> baseline, double[] baseCum,
                             EdgeCache cache, BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                             String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                             java.util.function.Function<double[], UnvisitedArea> findGminaCached,
                             List<UnvisitedArea> pool, double targetEffort, boolean allowWiggle,
                             int maxPasses, String debugPhase) {
        double effortBefore = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> visitedBefore = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);

        // Indeks: edgeKey → gminy przecinane przez geometrię krawędzi (czysty cache, deterministyczny).
        Map<String, Set<Integer>> edgeCrossCache = new HashMap<>();

        long callsStart = cache.misses(); // audyt v3.9: realne pytania do BRoutera zużyte przez ten tailPrune
        int deletedInmem = 0;
        int deletedExact = 0;
        int sweepDeleted = 0;
        int relocatedOnPath = 0;
        int reanchoredCnt = 0;
        int retargeted = 0;
        int exactAsked = 0;
        int movedSliced = 0;
        int movedBrouter = 0;
        int moveTries = 0;
        int latSkipped = 0;
        int wiggled = 0;
        double wiggleSaved = 0;
        int wiggleCalls = 0;
        int passes = 0;
        // ANATOMIA: powód ostatniej odmowy per punkt — koniec zgadywania „czemu nie skrócił".
        Map<double[], String> refusal = new java.util.IdentityHashMap<>();
        boolean changed = true;
        // DELETE-SWEEP (v3.9, „dosprzątanie do skutku"): po maxPasses pełnych passach kręcimy dalej
        // SAME PEWNIAKI (exclusive-empty → reanchor+delete; bez pytań/skracania/wiggle), aż przebieg
        // nic nie utnie. Domyka ogonki, których pokrycie POWSTAŁO na krawędziach scalonych w późnych
        // passach. Koszt ≈ tylko scalone krawędzie.
        while (changed && passes < maxPasses + 6) {
            changed = false;
            passes++;
            boolean deleteOnly = passes > maxPasses;
            int wiggledThisPass = 0;
            // crossCount: areaId → w ilu krawędziach BIEŻĄCEJ trasy występuje. Budowany NA NOWO
            // per pass (cache-hity JTS) — REPAIR i finalize poprzedniego passa nie muszą
            // utrzymywać go inkrementalnie; w trakcie passa aktualizacje inkrementalne.
            Map<Integer, Integer> crossCount = new HashMap<>();
            for (int i = 0; i < route.size() - 1; i++) {
                for (int a : edgeCrossings(route.get(i), route.get(i + 1), edgeCrossCache, cache, gminaIndex,
                        brouter, profile, alphaKmPerMeter)) {
                    crossCount.merge(a, 1, Integer::sum);
                }
            }
            // Krawędzie scalone po DELETE — geometria NIEZNANA do FINALIZE (jeden równoległy batch
            // BRouter po passie zamiast calla per kandydat). W trakcie passa wnoszą ∅ crossings —
            // konserwatywnie: mniej „kryte gdzie indziej" = mniej cięcia, zero ryzyka.
            List<double[][]> pendingPairs = new ArrayList<>();
            Set<String> pendingKeys = new HashSet<>();

            // Kandydaci wg PROXY detouru DESC (eIn.km+eOut.km−hav ≥ realny detour) — ZERO BRouter.
            // Zbieramy referencje punktów (nie indeksy) — route mutuje się w trakcie passu.
            record Cand(double[] point, double proxyDetour) {}
            List<Cand> cands = new ArrayList<>();
            for (int i = 1; i < route.size() - 1; i++) {
                double[] cur = route.get(i);
                if (isAnchor(cur, anchors)) continue;
                EdgeCache.EdgeInfo eIn = getEdge(route.get(i - 1), cur, cache, brouter, profile, alphaKmPerMeter);
                EdgeCache.EdgeInfo eOut = getEdge(cur, route.get(i + 1), cache, brouter, profile, alphaKmPerMeter);
                double proxy = eIn.distanceKm() + eOut.distanceKm()
                        - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                if (proxy >= TAIL_MIN_DETOUR_REAL_KM) cands.add(new Cand(cur, proxy));
            }
            cands.sort((x, y) -> Double.compare(y.proxyDetour(), x.proxyDetour()));

            for (Cand c : cands) {
                // Aktualny indeks kandydata (route mógł się zmienić po wcześniejszych DELETE/MOVE).
                int idx = -1;
                for (int i = 1; i < route.size() - 1; i++) {
                    if (route.get(i) == c.point()) { idx = i; break; }
                }
                if (idx < 0) continue; // już usunięty w tym passie
                double[] cur = c.point();
                double[] prev = route.get(idx - 1);
                double[] next = route.get(idx + 1);
                // Sąsiad scalony w TYM passie → krawędź pending (geometria nieznana do finalize) —
                // kandydat wraca w następnym passie na realnych danych.
                if (pendingKeys.contains(edgeKey(prev, cur)) || pendingKeys.contains(edgeKey(cur, next))) continue;
                Set<Integer> crIn = edgeCrossings(prev, cur, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                Set<Integer> crOut = edgeCrossings(cur, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                // exclusive' = gminy, których NIE przecina żadna inna krawędź trasy (v4: bez wiedzy
                // o eDirect — nie liczymy go; gminę „na prostej" rozstrzyga cięciwa/REPAIR).
                Set<Integer> exclusive = new HashSet<>();
                for (int a : crIn) {
                    int spurContrib = 1 + (crOut.contains(a) ? 1 : 0);
                    if (crossCount.getOrDefault(a, 0) <= spurContrib) exclusive.add(a);
                }
                for (int a : crOut) {
                    if (crIn.contains(a)) continue;
                    if (crossCount.getOrDefault(a, 0) <= 1) exclusive.add(a);
                }

                UnvisitedArea g = findGminaCached.apply(cur);
                if (exclusive.isEmpty()) {
                    // Wszystko z tego spuru kryte INNYMI legami trasy → punkt zbędny.
                    // v3.14: „punkt na drodze" rozstrzygamy W PAMIĘCI (pointToSegmentKm, 0 calli)
                    // zamiast eDir-peek (był ~94 calli/cykl). Spur blisko cięciwy = kotwica na krętej
                    // drodze, nie ogonek → zostaw (anty-młyn). Bump ≥1 km = realny zbędny ogonek → usuń.
                    if (pointToSegmentKm(cur, prev, next) < TAIL_MIN_LATERAL_KM) continue;
                    // Gmina tipu (waypoint usera!) dostaje kotwicę na swoim innym legu (invariant:
                    // wp NA śladzie), slot znika, scalona para czeka na finalize.
                    boolean okToDelete;
                    if (g != null) {
                        int gid = g.areaId();
                        int spurContrib = (crIn.contains(gid) ? 1 : 0) + (crOut.contains(gid) ? 1 : 0);
                        if (crossCount.getOrDefault(gid, 0) > spurContrib) {
                            int nIdx = reanchorOnOtherLeg(route, selected, baseline, baseCum, cache, brouter,
                                    profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount,
                                    pendingKeys, cur, idx, g);
                            if (nIdx < 0) {
                                // v3.13 (Czosnów): inne legi nie dały kotwicy (run za krótki) — przestaw
                                // punkt na najdłuższe przejście gminy po WŁASNYM dojeździe/powrocie
                                // („Czosnów też powinien coś mieć u siebie na śladzie").
                                if (relocateOntoOwnLeg(route, selected, baseline, baseCum, cache, brouter,
                                        profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount,
                                        prev, cur, next, idx, g)) {
                                    relocatedOnPath++;
                                    changed = true;
                                }
                                continue;
                            }
                            idx = nIdx;
                            reanchoredCnt++;
                            okToDelete = true;
                        } else {
                            // Gminy tipu nie kryje nic (spur muska ją <200m — graze). Usunięcie bez
                            // pewności = strata gminy; rozstrzygnie DOKŁADNE pytanie niżej (v3.11).
                            if (deleteOnly) continue;
                            okToDelete = false; // fall-through do bloku exact
                        }
                    } else {
                        selected.removeIf(s -> s.point() == cur); // no-owner artefakt — zdejmij ewentualny SeedSel
                        okToDelete = true;
                    }
                    if (okToDelete) {
                        route.remove(idx);
                        for (int a : crIn) crossCount.merge(a, -1, Integer::sum);
                        for (int a : crOut) crossCount.merge(a, -1, Integer::sum);
                        pendingPairs.add(new double[][]{prev, next});
                        pendingKeys.add(edgeKey(prev, next));
                        if (deleteOnly) sweepDeleted++; else deletedInmem++;
                        changed = true;
                        continue;
                    }
                }
                if (deleteOnly) continue; // sweep: tylko pewniaki — bez pytań/skracania/wiggle

                // STERCZENIE W BOK (v3.10): kręta droga ≠ ogonek. Kandydat z dużym proxy-detourem,
                // ale leżący blisko linii prev→next, to najpewniej punkt na zakręcie (np. kotwica
                // na środku 25-km legu: real 48 vs hav 36 km dawało 12 km fałszywego „objazdu").
                if (pointToSegmentKm(cur, prev, next) < TAIL_MIN_LATERAL_KM) {
                    latSkipped++;
                    refusal.put(cur, "bok<1km");
                    continue;
                }

                // ===== SPUR JEDYNY-KONTAKT — DECYZJA W PAMIĘCI (v3.14, koniec spekulacji) =====
                // Dawniej: 1 pytanie o eDir DLA KAŻDEGO podejrzanego (asked=622), z czego ~520 to
                // „zostaw". Teraz: najpierw skróć/przestaw W PAMIĘCI (slice + granica), a eDir pytamy
                // TYLKO gdy spur kredytuje OBCĄ gminkę (Koluszki-Różyca, rzadkie).
                if (g == null) { refusal.put(cur, "exclusive-bez-gminy"); continue; }
                EdgeCache.EdgeInfo eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter);
                EdgeCache.EdgeInfo eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter);

                // (1) OUT-AND-BACK tą samą drogą → CIĘCIE obu geometrii, ZERO calli.
                SpurSlice ss = trySliceShorten(eIn, eOut, g, alphaKmPerMeter);
                if (ss != null && ss.gain() > TAIL_MOVE_MIN_GAIN) {
                    Set<Integer> slIn = gminaIndex.visitedAreaIds(ss.eInNew().geometry());
                    Set<Integer> slOut = gminaIndex.visitedAreaIds(ss.eOutNew().geometry());
                    boolean keepsAll = true;
                    for (int a : exclusive) if (!slIn.contains(a) && !slOut.contains(a)) { keepsAll = false; break; }
                    if (keepsAll) {
                        double[] newWp = ss.newWp();
                        cache.getOrCompute(prev[0], prev[1], newWp[0], newWp[1], pts -> ss.eInNew());
                        cache.getOrCompute(newWp[0], newWp[1], next[0], next[1], pts -> ss.eOutNew());
                        swapEntryPoint(selected, cur, newWp, baseline, baseCum);
                        route.set(idx, newWp);
                        for (int a : crIn) crossCount.merge(a, -1, Integer::sum);
                        for (int a : crOut) crossCount.merge(a, -1, Integer::sum);
                        for (int a : edgeCrossings(prev, newWp, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) crossCount.merge(a, 1, Integer::sum);
                        for (int a : edgeCrossings(newWp, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) crossCount.merge(a, 1, Integer::sum);
                        movedSliced++; changed = true; continue;
                    }
                }

                // (2) RELOKACJA na PŁYTKIE przejście WŁASNEJ nogi (granica+depth): własna noga = slice
                //     (0 calli), nowa noga powrotna = 1 call. To „wjedź na paręset metrów i zawróć"
                //     (point 25/Leoncin) oraz „ślad idzie przez Drzewicę — przestaw punkt" (Drzewica).
                if (relocateShallowOwnLeg(route, selected, baseline, baseCum, cache, brouter, profile,
                        alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount, exclusive,
                        prev, cur, next, idx, g, eIn, eOut)) {
                    relocatedOnPath++; changed = true; continue;
                }

                // (3) Brak OBCEJ gminki (exclusive = tylko własna) → nie ma czego ratować prostą
                //     drogą; głęboka jedyna gmina (jezioro/ślepa/most) → ZOSTAW (legit). BEZ eDir.
                boolean hasForeign = false;
                for (int a : exclusive) if (a != g.areaId()) { hasForeign = true; break; }
                if (!hasForeign) { refusal.put(cur, "głęboka-jedyna"); continue; }

                // (4) MUSKANE GMINKI (Koluszki-Różyca, RZADKIE) — dopiero TU pytamy o prostą drogę.
                EdgeCache.EdgeInfo eDir = getEdge(prev, next, cache, brouter, profile, alphaKmPerMeter);
                exactAsked++;
                Set<Integer> crDir = edgeCrossings(prev, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                Set<Integer> lost = new HashSet<>();
                for (int aid : exclusive) if (!crDir.contains(aid)) lost.add(aid);

                if (lost.isEmpty()) {
                    // Cięcie nic nie traci — DOKŁADNIE potwierdzone. Gmina punktu zachowuje uchwyt:
                    if (g != null && crDir.contains(g.areaId())) {
                        // RELOCATE-ON-PATH (v3.1 wskrzeszony, teraz exact): punkt NA prostą drogę,
                        // dokładnie tam gdzie przecina gminę. Zero objazdu, slice z pamięci.
                        int sIdx = midpointOfCrossing(eDir.geometry(), g);
                        if (sIdx > 0 && sIdx < eDir.geometry().size() - 1) {
                            double[] newWp = eDir.geometry().get(sIdx).clone();
                            seedSlicedEdges(cache, eDir, prev, next, sIdx, alphaKmPerMeter);
                            swapEntryPoint(selected, cur, newWp, baseline, baseCum);
                            route.set(idx, newWp);
                            for (int a : crIn) crossCount.merge(a, -1, Integer::sum);
                            for (int a : crOut) crossCount.merge(a, -1, Integer::sum);
                            for (int a : edgeCrossings(prev, newWp, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
                                crossCount.merge(a, 1, Integer::sum);
                            }
                            for (int a : edgeCrossings(newWp, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
                                crossCount.merge(a, 1, Integer::sum);
                            }
                            relocatedOnPath++;
                            changed = true;
                            continue;
                        }
                    }
                    if (g != null) {
                        int gid = g.areaId();
                        int contrib = (crIn.contains(gid) ? 1 : 0) + (crOut.contains(gid) ? 1 : 0);
                        if (crossCount.getOrDefault(gid, 0) > contrib) {
                            int nIdx = reanchorOnOtherLeg(route, selected, baseline, baseCum, cache, brouter,
                                    profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount,
                                    pendingKeys, cur, idx, g);
                            if (nIdx < 0) continue;
                            idx = nIdx;
                            reanchoredCnt++;
                        } else {
                            selected.removeIf(s -> s.point() == cur); // graze-artefakt — sel kłamał
                        }
                    } else {
                        selected.removeIf(s -> s.point() == cur);
                    }
                    route.remove(idx);
                    for (int a : crIn) crossCount.merge(a, -1, Integer::sum);
                    for (int a : crOut) crossCount.merge(a, -1, Integer::sum);
                    for (int a : crDir) crossCount.merge(a, 1, Integer::sum); // krawędź już policzona
                    deletedExact++;
                    changed = true;
                    continue;
                }

                // PRZEJMIJ-I-PRZESTAW (v3.11, przykład usera Koluszki/Różyca): po cięciu wypadłaby
                // muskana gminka → punkt PRZEPROWADZA SIĘ do niej, w miejsce najbliższe prostej
                // drodze („odbić w bok po gminkę, zawrócić i pognać dalej"). Tylko gdy lost to
                // NIE jest wyłącznie własna gmina punktu (tę skraca MOVE niżej).
                boolean ownOnly = g != null && lost.size() == 1 && lost.contains(g.areaId());
                if (!ownOnly) {
                    record RtCand(double[] pt, UnvisitedArea owner) {}
                    List<RtCand> rtCands = new ArrayList<>();
                    for (int aid : lost) {
                        UnvisitedArea la = idToArea.get(aid);
                        if (la == null) continue;
                        for (double[] sp : gminaIndex.samplePointsFor(la)) rtCands.add(new RtCand(sp, la));
                        for (double[] gp : gminaIndex.grazePointsFor(la)) rtCands.add(new RtCand(gp, la));
                    }
                    rtCands.sort((x, y) -> Double.compare(
                            velomarker.service.planning.WaypointSelector.haversineKm(prev, x.pt())
                                    + velomarker.service.planning.WaypointSelector.haversineKm(x.pt(), next),
                            velomarker.service.planning.WaypointSelector.haversineKm(prev, y.pt())
                                    + velomarker.service.planning.WaypointSelector.haversineKm(y.pt(), next)));
                    boolean retargetDone = false;
                    int rtTried = 0;
                    for (RtCand rc : rtCands) {
                        if (rtTried >= 2) break;
                        double[] cnd = rc.pt().clone();
                        double gainMax = eIn.effort() + eOut.effort()
                                - velomarker.service.planning.WaypointSelector.haversineKm(prev, cnd)
                                - velomarker.service.planning.WaypointSelector.haversineKm(cnd, next);
                        if (gainMax <= TAIL_MOVE_MIN_GAIN) continue; // linijka — bez szans, bez calla
                        rtTried++;
                        moveTries++;
                        EdgeCache.EdgeInfo nIn = getEdge(prev, cnd, cache, brouter, profile, alphaKmPerMeter);
                        EdgeCache.EdgeInfo nOut = getEdge(cnd, next, cache, brouter, profile, alphaKmPerMeter);
                        double gain = eIn.effort() + eOut.effort() - nIn.effort() - nOut.effort();
                        if (gain <= TAIL_MOVE_MIN_GAIN) continue;
                        Set<Integer> crNIn = edgeCrossings(prev, cnd, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                        Set<Integer> crNOut = edgeCrossings(cnd, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                        boolean keepsAll = true;
                        for (int a : exclusive) {
                            if (!crNIn.contains(a) && !crNOut.contains(a)) { keepsAll = false; break; }
                        }
                        if (!keepsAll) continue; // nowy wypad musi kryć WSZYSTKO, co stary trzymał
                        // Własna gmina punktu: kryta gdzie indziej → kotwica tam; inaczej jest
                        // w exclusive ⊆ nowe legi → uchwyt dostanie na nowym legu po przestawieniu.
                        if (g != null) {
                            int gid = g.areaId();
                            int contrib = (crIn.contains(gid) ? 1 : 0) + (crOut.contains(gid) ? 1 : 0);
                            if (crossCount.getOrDefault(gid, 0) > contrib) {
                                int nIdx = reanchorOnOtherLeg(route, selected, baseline, baseCum, cache, brouter,
                                        profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount,
                                        pendingKeys, cur, idx, g);
                                if (nIdx >= 0) idx = nIdx; // fail → stary sel zostaje na cur i zaraz go nadpiszemy swapem niżej
                            }
                        }
                        // Stary sel (jeśli wciąż wskazuje cur) przejmuje gminkę: punkt + obszar.
                        SeedSel oldSel = null;
                        for (SeedSel s : selected) {
                            if (s.point() == cur) { oldSel = s; break; }
                        }
                        if (oldSel != null) selected.remove(oldSel);
                        selected.add(new SeedSel(rc.owner(), cnd, orderKey(cnd, baseline, baseCum), 0.0,
                                minDistToBaselineKm(cnd, baseline)));
                        route.set(idx, cnd);
                        for (int a : crIn) crossCount.merge(a, -1, Integer::sum);
                        for (int a : crOut) crossCount.merge(a, -1, Integer::sum);
                        for (int a : crNIn) crossCount.merge(a, 1, Integer::sum);
                        for (int a : crNOut) crossCount.merge(a, 1, Integer::sum);
                        retargeted++;
                        changed = true;
                        retargetDone = true;
                        break;
                    }
                    if (retargetDone) continue;
                }

                // Retarget nie znalazł krótkiego wypadu na muskaną gminkę → zostaw (rzadkie).
                // Operator MOVE-665-prób + wiggle USUNIĘTE (v3.14): skracanie robi teraz relokacja
                // w pamięci wyżej (slice + 1 call), a nie spekulacyjne routowanie kandydatów.
                refusal.put(cur, "muskana-nieusuwalna");
            }

            // FINALIZE passa: scalone pary policz JEDNYM równoległym batchem BRouter (te krawędzie
            // i tak są potrzebne dla trasy — to jedyne calle poza dokładnymi pytaniami i skracaniem).
            if (!pendingPairs.isEmpty()) {
                prewarmPairs(pendingPairs, cache, brouter, profile, alphaKmPerMeter);
                pendingPairs.clear();
                pendingKeys.clear();
            }
        }

        // ŁAŃCUSZKI (v3.12): spury wielopunktowe (kotwica wbita w ogonku + tip), na które cięcia
        // pojedyncze są ślepe — wzorzec Zakroczym/Głusk i widelec Wiersze ze screenów usera.
        ChainResult chains = chainSpurResolve(route, selected, anchors, baseline, baseCum, cache,
                brouter, profile, alphaKmPerMeter, gminaIndex, findGminaCached, idToArea, edgeCrossCache);

        if (deletedInmem == 0 && deletedExact == 0 && sweepDeleted == 0 && relocatedOnPath == 0
                && retargeted == 0 && movedSliced == 0 && movedBrouter == 0
                && reanchoredCnt == 0 && wiggled == 0 && chains.resolved() == 0) {
            log.info("ALNS2 TAIL-PRUNE v5: nic do zrobienia (passes={}, asked={}, calls={}), effort={} ({}%)",
                    new Object[]{passes, exactAsked, cache.misses() - callsStart, Math.round(effortBefore),
                            Math.round(effortBefore * 100.0 / targetEffort)});
            return effortBefore;
        }

        // BEZ reorderu i bez guardu: delete=splice / move=in-place / retarget=in-place zachowują
        // porządek; każda decyzja podjęta na DOKŁADNYCH danych (eDir) — zero napraw po fakcie.
        double realEffort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(
                concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        Set<Integer> dropped = new HashSet<>(visitedBefore);
        dropped.removeAll(visitedAfter);
        log.info("ALNS2 TAIL-PRUNE v5 (exact): deleted-inmem={}, deleted-exact={}, sweep-deleted={}, relocated-on-path={}, retargeted={}, chains={} (chain-asked={}), reanchored={}, moved-sliced={}, moved-brouter={} (tries={}), lat-skipped={}, asked={}, wiggled={} (saved={} effort, ~{} calls), passes={} (max {} + sweep), dropped={}, calls={}, effort {}→{} ({}%→{}%)",
                new Object[]{deletedInmem, deletedExact, sweepDeleted, relocatedOnPath, retargeted,
                        chains.resolved(), chains.asked(),
                        reanchoredCnt, movedSliced, movedBrouter, moveTries, latSkipped, exactAsked,
                        wiggled, Math.round(wiggleSaved), wiggleCalls, passes, maxPasses, dropped.size(),
                        cache.misses() - callsStart,
                        Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort)});
        // ANATOMIA OCALAŁYCH SPURÓW (v3.12): pełna prawda o każdym garbie ≥1 km — sort po
        // wielkości objazdu DESC (dotąd lista cięta po kolejności trasy UKRYWAŁA najgorsze, np.
        // spur Zakroczymia nigdy się nie zmieścił). Zero nowych calli: crossCount i przecięcia
        // z cache; realny objazd tylko gdy prosta droga była już policzona w passach.
        if (debugGeoJson) {
            Map<Integer, Integer> ccFinal = new HashMap<>();
            for (int i = 0; i < route.size() - 1; i++) {
                for (int a : edgeCrossings(route.get(i), route.get(i + 1), edgeCrossCache, cache, gminaIndex,
                        brouter, profile, alphaKmPerMeter)) {
                    ccFinal.merge(a, 1, Integer::sum);
                }
            }
            record KeptSpur(int idx, double[] pt, double proxy, double lat, String gmina,
                            String own, String holds) {}
            List<KeptSpur> kept = new ArrayList<>();
            for (int i = 1; i < route.size() - 1; i++) {
                double[] cur = route.get(i);
                if (isAnchor(cur, anchors)) continue;
                double[] pv = route.get(i - 1);
                double[] nx = route.get(i + 1);
                EdgeCache.EdgeInfo eIn = getEdge(pv, cur, cache, brouter, profile, alphaKmPerMeter);
                EdgeCache.EdgeInfo eOut = getEdge(cur, nx, cache, brouter, profile, alphaKmPerMeter);
                double proxy = eIn.distanceKm() + eOut.distanceKm()
                        - velomarker.service.planning.WaypointSelector.haversineKm(pv, nx);
                if (proxy < 1.0) continue; // pokazuj też garby poniżej progu cięcia (wzorzec „punkt 10")
                double lat = pointToSegmentKm(cur, pv, nx);
                UnvisitedArea g = findGminaCached.apply(cur);
                String own;
                if (g == null) {
                    own = "BEZ-GMINY";
                } else {
                    int gid = g.areaId();
                    int contrib = (edgeCrossings(pv, cur, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter).contains(gid) ? 1 : 0)
                            + (edgeCrossings(cur, nx, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter).contains(gid) ? 1 : 0);
                    own = ccFinal.getOrDefault(gid, 0) > contrib
                            ? "GMINA-MA-DRUGI-KONTAKT(!)" : "JEDYNY-KONTAKT";
                }
                Set<Integer> crI = edgeCrossings(pv, cur, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                Set<Integer> crO = edgeCrossings(cur, nx, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                StringBuilder holds = new StringBuilder();
                for (int a : crI) {
                    int contribA = 1 + (crO.contains(a) ? 1 : 0);
                    if (ccFinal.getOrDefault(a, 0) <= contribA) {
                        UnvisitedArea ha = idToArea.get(a);
                        if (ha != null) holds.append(holds.length() > 0 ? "," : "").append(ha.name());
                    }
                }
                for (int a : crO) {
                    if (crI.contains(a)) continue;
                    if (ccFinal.getOrDefault(a, 0) <= 1) {
                        UnvisitedArea ha = idToArea.get(a);
                        if (ha != null) holds.append(holds.length() > 0 ? "," : "").append(ha.name());
                    }
                }
                kept.add(new KeptSpur(i, cur, proxy, lat, g != null ? g.name() : "?", own,
                        holds.length() > 0 ? holds.toString() : "NIC"));
            }
            kept.sort((x, y) -> Double.compare(y.proxy(), x.proxy()));
            StringBuilder keptSb = new StringBuilder();
            int shown = 0;
            for (KeptSpur ks : kept) {
                if (shown++ >= 25) break;
                keptSb.append(String.format(java.util.Locale.ROOT,
                        " [#%d %.4f,%.4f → %s | garb~%.1fkm bok=%.1fkm | gmina-punktu: %s | trzyma-na-wyłączność: %s | odmowa: %s]",
                        ks.idx(), ks.pt()[0], ks.pt()[1], ks.gmina(), ks.proxy(), ks.lat(), ks.own(), ks.holds(),
                        refusal.getOrDefault(ks.pt(), "-")));
            }
            if (shown > 0) log.info("ALNS2 SPUR-ANATOMIA ({} garbów ≥1km, top {} po wielkości):{}",
                    new Object[]{kept.size(), Math.min(shown, 25), keptSb});
            debugGeometry(debugPhase,
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter), route,
                    routeRealKm(route, cache, brouter, profile, alphaKmPerMeter));
        }
        return realEffort;
    }

    /** Wynik GROW-NEAR: realny effort po wstawkach + ile gmin dorzucono. */
    private record GrowNearResult(double effort, int inserted) {}

    private record NearCand(UnvisitedArea area, double dist, double score) {}

    /** Promień doboru kandydatów grow-near wokół aktualnej trasy. */
    private static final double GROW_NEAR_R_KM = 25.0;

    /** Kandydaci ≤25 km od AKTUALNEJ trasy: score = reward×(1+enclosed)/max(0.5,dist), sort DESC.
     *  Wołane na wejściu batch-grow i przy refreshu po wyczerpaniu listy (v3.7). */
    private List<NearCand> nearCandidates(List<UnvisitedArea> pool, Set<Integer> visited, Set<Integer> skip,
                                          List<double[]> route, GminaIndex gminaIndex, Map<String, Double> rewards) {
        List<NearCand> cands = new ArrayList<>();
        for (UnvisitedArea a : pool) {
            if (visited.contains(a.areaId()) || skip.contains(a.areaId())) continue;
            double d = gminaIndex.distToRoute(a, route);
            if (d > GROW_NEAR_R_KM) continue;
            double rw = rewards.getOrDefault(rewardCategoryKey(a), 1.0);
            double enc = gminaIndex.enclosedFraction(a.areaId(), visited);
            cands.add(new NearCand(a, d, rw * (1.0 + enc) / Math.max(0.5, d)));
        }
        cands.sort((x, y) -> Double.compare(y.score(), x.score()));
        return cands;
    }

    /**
     * GROW-NEAR v3.8 — BATCH-GROW (wzorzec init-grow, decyzja usera): batche po {@code batchSize} +
     * twoOptIncremental NA BIEŻĄCO (spur rozplątany zanim się utrwali) + estymator L2
     * (haversine × samokalibrowany factor) z realnym checkpointem co {@code checkpointEvery}
     * batchy / przy stopie. Zastępuje RATIO-GRAB, który przestrzeliwał (compact-ratio 13/gminę
     * vs marginalna wstawka ~28/gminę → 61%↔147% oscylacja → tailPrune mielił 1-2k calli/cykl).
     * Checkpoint = pełny 2opt + real (stop nie odpala na chwilowo spuchniętej kolejności);
     * wyczerpanie kandydatów → refresh listy na aktualnej trasie (max 2).
     * Islands-check + kredyt-verify RAZ po pętli.
     * Dobór score-based bez zmian: {@code reward × (1+enclosed) / max(0.5, distToRoute)}.
     * Tryby (v3.8): zwykły grow (12, 3) i TOP-UP — domykacz pasma (6, 1: pomiar po każdej szóstce,
     * nie da się przestrzelić).
     */
    private GrowNearResult growNear(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                                    List<double[]> baseline, double[] baseCum, EdgeCache cache,
                                    BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                    String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                    List<UnvisitedArea> pool, Map<String, Double> rewards, double growTarget,
                                    int batchSize, int checkpointEvery, int maxInserts) {
        final int BATCH = batchSize;
        final int CHECKPOINT_EVERY = checkpointEvery;
        // v3.17b: maxInserts > 0 = dobieranie wg PROPORCJI (wstaw dokładnie tyle gmin, zmierz raz na
        // końcu — bez sterowania effort-targetem). 0 = stary tryb effort-driven (nieużywany po v3.17b).
        long callsStart = cache.realCalls(); // v3.18: realne strzały (nie misses)
        EvalResult ev = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
        double effort = ev.effort();
        if (effort >= growTarget) return new GrowNearResult(effort, 0);
        Set<Integer> visited = ev.visited();
        List<double[]> geomRef = ev.geometry();

        Set<Integer> myInsertAreas = new HashSet<>();
        List<NearCand> cands = nearCandidates(pool, visited, myInsertAreas, route, gminaIndex, rewards);

        // Estymator L2: effort ≈ haversine(route) × factor. Factor startuje z realnego stanu
        // trasy i jest rekalibrowany na checkpointach — samodostosowuje się do terenu/profilu.
        double hav = routeHaversineKm(route);
        double effortFactor = hav > 1 ? effort / hav : 2.0;
        int inserted = 0;
        int ci = 0;
        int batchCount = 0;
        int checkpoints = 0;
        int refreshes = 0;
        double lastMeasured = effort; // do undo: ostatni REALNY pomiar (wejściowy jest realny)
        int sinceMeasure = 0;         // ile wstawek od ostatniego realnego pomiaru
        List<SeedSel> allInserts = new ArrayList<>();
        Set<Integer> retriedCentroid = new HashSet<>();
        while (true) {
            if (maxInserts > 0 && inserted >= maxInserts) break; // v3.17b: limit dobierania wg proporcji
            List<SeedSel> batchSels = new ArrayList<>();
            while (batchSels.size() < BATCH && (maxInserts <= 0 || inserted + batchSels.size() < maxInserts)
                    && ci < cands.size()) {
                NearCand nc = cands.get(ci++);
                if (visited.contains(nc.area().areaId())) continue;
                if (myInsertAreas.contains(nc.area().areaId())) continue;
                double[] ep = gminaIndex.deepestInteriorPoint(nc.area().areaId()); // RUNDA 51: najgłębszy
                if (ep == null) ep = sampleNearestToGeometry(gminaIndex.samplePointsFor(nc.area()), null, geomRef);
                if (ep == null) continue;
                SeedSel sel = new SeedSel(nc.area(), ep, orderKey(ep, baseline, baseCum), 0.0,
                        minDistToBaselineKm(ep, baseline));
                selected.add(sel);
                batchSels.add(sel);
                allInserts.add(sel);
                myInsertAreas.add(nc.area().areaId());
                // v3.9: WSUWANIE zamiast tasowania — punkt wchodzi tam, gdzie najmniej nadkłada
                // (linijka), reszta kolejności bez zmian → BRouter pyta tylko o dojazd do nowej
                // gminy (~2/szt), nie o pół trasy po każdym przetasowaniu z hilberta.
                route.add(cheapestInsertPos(route, ep), ep);
            }
            if (batchSels.isEmpty()) {
                // v3.7: wyczerpanie listy ≠ koniec wzrostu — kandydaci liczeni na trasie SPRZED
                // wstawek, a nowe segmenty mają własnych sąsiadów ≤25 km (cykl 1 stawał na 88%
                // targetu po 48 wstawkach). Refresh na aktualnej trasie, max 2.
                if (refreshes >= 2) break;
                refreshes++;
                EvalResult rev = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
                effort = rev.effort();
                visited = rev.visited();
                geomRef = rev.geometry();
                hav = routeHaversineKm(route);
                if (hav > 1) effortFactor = effort / hav;
                lastMeasured = effort;
                sinceMeasure = 0;
                if (effort >= growTarget) break;
                cands = nearCandidates(pool, visited, myInsertAreas, route, gminaIndex, rewards);
                ci = 0;
                if (cands.isEmpty()) break;
                continue;
            }
            inserted += batchSels.size();
            sinceMeasure += batchSels.size();
            batchCount++;
            // v3.22 (Twoje): pełny 2opt po KAŻDYM dobraniu w końcowych cyklach — tanie CPU, skraca
            // trasę na bieżąco (mniej brudu przed cięciem ogonków). Bez rebuildu z hilberta (v3.9).
            twoOptLogged(route, "growNear-batch" + batchCount);
            hav = routeHaversineKm(route);
            double est = hav * effortFactor;
            boolean doReal = (batchCount % CHECKPOINT_EVERY == 0) || est >= growTarget;
            if (doReal) {
                // Pomiar real na uporządkowanej kolejności (2opt już zrobiony co batch wyżej).
                hav = routeHaversineKm(route);
                effort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                checkpoints++;
                if (hav > 1) effortFactor = effort / hav;
                // UNDO porcji (v3.10, wzór init-grow): blisko celu porcja 3× droższa od średniej
                // = złapaliśmy DALEKIE gminy (topup 21:31 przestrzelił do 120% growTargetu na
                // ostatniej porcji ~125 effortu/szt) → cofnij ostatnią porcję i zakończ dosypkę.
                if (lastMeasured > 0 && sinceMeasure > 0 && effort >= 0.9 * growTarget) {
                    double marginal = (effort - lastMeasured) / sinceMeasure;
                    double ratio = effort / Math.max(1, selected.size());
                    if (marginal > 3 * ratio) {
                        for (SeedSel s : batchSels) {
                            selected.remove(s);
                            route.remove((Object) s.point());
                            allInserts.remove(s);
                            myInsertAreas.remove(s.area().areaId());
                            inserted--;
                        }
                        twoOptLogged(route, "growNear-undo");
                        effort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
                        log.info("ALNS2 BATCH-GROW undo porcji {}: marginal={}/gminę > 3×ratio={} (dalekie gminy) → stop na {}% growTargetu",
                                new Object[]{batchCount, Math.round(marginal), Math.round(ratio),
                                        Math.round(effort * 100.0 / growTarget)});
                        break;
                    }
                }
                lastMeasured = effort;
                sinceMeasure = 0;
            } else {
                effort = est;
            }
            if (effort >= growTarget) break;
        }

        // FINALIZACJA (raz, nie per batch): pełny 2opt (bez rebuildu — v3.9) → real →
        // islands-check → kredyt-verify.
        twoOptLogged(route, "growNear-final");
        effort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        int islands = 0;
        for (SeedSel s : allInserts) {
            int pos = -1;
            for (int i = 1; i < route.size() - 1; i++) {
                if (route.get(i) == s.point()) { pos = i; break; }
            }
            if (pos < 0) continue;
            boolean fail = failedEdges.contains(edgeKey(route.get(pos - 1), s.point()))
                    || failedEdges.contains(edgeKey(s.point(), route.get(pos + 1)));
            if (fail) {
                selected.remove(s);
                route.remove((Object) s.point()); // v3.9: bez rebuildu route czyścimy ręcznie
                myInsertAreas.remove(s.area().areaId());
                islands++;
                inserted--;
            }
        }
        if (islands > 0) {
            twoOptLogged(route, "growNear-islands");
            effort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        }
        // Kredyt-verify (wp 206): max 3 rundy (1. wykrycie → retry centroid, 2. weryfikacja centroidu → delete).
        for (int vr = 0; vr < 3; vr++) {
            visited = gminaIndex.visitedAreaIds(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            if (!verifyInsertCredit(route, selected, anchorOnly, baseline, baseCum, cache, brouter,
                    profile, alphaKmPerMeter, myInsertAreas, retriedCentroid, visited)) break;
            effort = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
        }
        int credited = 0;
        for (SeedSel s : selected) if (myInsertAreas.contains(s.area().areaId()) && s.score() == 0.0) credited++;
        log.info("ALNS2 BATCH-GROW: batches={}, inserted={}, islands={}, checkpoints={}, refreshes={}, factor={}, calls={}, effort → {} ({}% growTarget)",
                new Object[]{batchCount, Math.min(inserted, credited), islands, checkpoints, refreshes,
                        String.format(java.util.Locale.ROOT, "%.2f", effortFactor),
                        cache.realCalls() - callsStart,
                        Math.round(effort), Math.round(effort * 100.0 / growTarget)});
        return new GrowNearResult(effort, Math.min(inserted, credited));
    }

    /** Score-sentinel wstawek ENCLOSED-FILL — TRIM (sort score ASC, usuwa front) nigdy ich nie rusza:
     *  otoczona dziura w środku blobu jest gorsza niż przestrzał budżetu (decyzja usera 2026-06-12). */
    private static final double ENCLOSED_PROTECTED_SCORE = Double.MAX_VALUE;
    /** TRIM peeling: cap rund cięcia w jednej iteracji TRIM (proporcja zbiega, to tylko bezpiecznik). */
    private static final int TRIM_MAX_PEELS = 8;
    /** TRIM peeling: anty-dzielenie-przez-0 dla kosztu-objazdu (km) — kolateral (detour≈0) → reward/EPS = ogromne = nie tnij. */
    private static final double TRIM_DETOUR_EPS = 0.05;
    /** TRIM peeling: poniżej tego spadku effortu (km+α·m) runda uznana za „bez postępu" → stop (ucięto kolateral). */
    private static final double TRIM_PROGRESS_EPS = 1.0;
    /** Próg otoczenia dla ENCLOSED-FILL: 1.0 = wszystkie 8/8 k-NN sąsiadów zaliczone (decyzja usera). */
    private static final double ENCLOSED_THRESHOLD = 1.0;

    /**
     * ENCLOSED-FILL (v3.4, decyzja usera): seed KOŃCZY się gwarancją braku DZIUR — gmin otoczonych
     * z każdej strony zaliczonymi ({@code enclosedFraction ≥ ENCLOSED_THRESHOLD}). Takie dziury
     * są tanie (ślad je opływa → mały detour), a leżą W ŚRODKU blobu — user nie chce do nich wracać.
     * Domyka WSZYSTKIE bezwarunkowo; budżet równa KOŃCÓWKA cyklu (tailPrune/TOP-UP/TRIM) — v3.8
     * wycięła stąd cięcie peryferii (cut-24 → clean −13 pp → grow odkupywał = churn ~1500 calli/cykl
     * i punkt stały 92%). Wstawki dostają {@link #ENCLOSED_PROTECTED_SCORE} → TRIM ich nie rusza.
     * Zwraca liczbę domknięć.
     */
    private int enclosedFill(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                             List<double[]> baseline, double[] baseCum, EdgeCache cache,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                             String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                             List<UnvisitedArea> pool, double targetEffort) {
        long callsStart = cache.realCalls(); // v3.18: realne strzały (nie misses)
        cache.setReason("dziura-otoczona"); // v3.16: ENCLOSED-FILL = łatanie dziur otoczonych
        int filled = 0;
        int unreachable = 0;
        for (int iter = 0; iter < 3; iter++) {
            EvalResult ev = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
            Set<Integer> visited = ev.visited();
            List<double[]> geom = ev.geometry();
            // v3.15: dziury otoczone wg REALNEGO sąsiedztwa wielokątów (port JTS, touches) — gmina
            // nieprzecięta, której WSZYSCY sąsiedzi (cross-border, bez progu na liczbę) są zaliczeni dookoła.
            // Zastępuje centroidowy enclosedFraction (mylił przy nieregularnych kształtach).
            Set<Integer> enclosed = gminaIndex.enclosedUnvisited(visited);
            List<UnvisitedArea> holes = new ArrayList<>();
            for (UnvisitedArea a : pool) {
                if (enclosed.contains(a.areaId())) holes.add(a);
            }
            if (holes.isEmpty()) break;
            holes.sort((x, y) -> Double.compare(gminaIndex.distToRoute(x, route), gminaIndex.distToRoute(y, route)));
            List<SeedSel> added = new ArrayList<>();
            for (UnvisitedArea a : holes) {
                double[] ep = gminaIndex.deepestInteriorPoint(a.areaId()); // RUNDA 51: najgłębszy
                if (ep == null) ep = sampleNearestToGeometry(gminaIndex.samplePointsFor(a), null, geom);
                if (ep == null) continue;
                SeedSel sel = new SeedSel(a, ep, orderKey(ep, baseline, baseCum), ENCLOSED_PROTECTED_SCORE,
                        minDistToBaselineKm(ep, baseline));
                selected.add(sel);
                added.add(sel);
            }
            if (added.isEmpty()) break;
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "enclosedFill");
            // Kredyt-verify: otoczona dziura też może być nieosiągalna (most/wyspa) → retry centroid → usuń.
            Set<Integer> vis = gminaIndex.visitedAreaIds(
                    concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            for (SeedSel s : new ArrayList<>(added)) {
                if (vis.contains(s.area().areaId())) continue;
                double[] c = gminaIndex.deepestInteriorPoint(s.area().areaId()); // RUNDA 51: najgłębszy (nie centroid)
                if (c == null) c = new double[]{s.area().lng(), s.area().lat()};
                swapEntryPoint(selected, s.point(), c, baseline, baseCum);
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "enclosedFill-centroid");
            vis = gminaIndex.visitedAreaIds(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            for (SeedSel s : new ArrayList<>(added)) {
                if (!vis.contains(s.area().areaId())) { selected.remove(s); added.remove(s); unreachable++; }
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "enclosedFill-verify");
            filled += added.size();
        }
        if (filled > 0 || unreachable > 0) {
            double eff = routeEffortViaCache(route, cache, brouter, profile, alphaKmPerMeter);
            log.info("ALNS2 ENCLOSED-FILL: domknięto={} dziur (8/8 otoczone), nieosiągalne={}, calls={}, effort → {} ({}%)",
                    new Object[]{filled, unreachable, cache.realCalls() - callsStart, Math.round(eff),
                            Math.round(eff * 100.0 / targetEffort)});
        }
        return filled;
    }

    /**
     * v3.16 B4: dosadza NIEodwiedzone gminy ≤3 km od trasy (port JTS {@code unvisitedWithinKm}) do
     * {@code hiBand} — łatanie „przy trasie / do środka", które NIE wypycha frontu (w przeciwieństwie
     * do growNear score-based, który brał dalekie gminy i tworzył nowe dziury obok Sochaczewa/Iłowa).
     * STOP gdy bliskich dziur brak — lepiej skończyć poniżej hiBand niż wypchnąć front i zrobić nowe
     * dziury. Sort po distToRoute ASC; kredyt-verify (retry centroid → usuń) jak enclosedFill; wstawki
     * chronione przed TRIM (ENCLOSED_PROTECTED_SCORE). Zwraca liczbę zaliczonych wstawek.
     */
    private int fillNearHoles(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                              List<double[]> baseline, double[] baseCum, EdgeCache cache,
                              BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                              String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                              List<UnvisitedArea> pool, double hiBand) {
        cache.setReason("dziura-przy-trasie");
        int filled = 0;
        for (int iter = 0; iter < 3; iter++) {
            EvalResult ev = evalRoute(route, cache, brouter, profile, alphaKmPerMeter, gminaIndex);
            if (ev.effort() >= hiBand) break;
            Set<Integer> visited = ev.visited();
            List<double[]> geom = ev.geometry();
            Set<Integer> near = gminaIndex.unvisitedWithinKm(geom, visited, 3.0);
            if (near.isEmpty()) break;
            List<UnvisitedArea> holes = new ArrayList<>();
            for (UnvisitedArea a : pool) if (near.contains(a.areaId())) holes.add(a);
            holes.sort((x, y) -> Double.compare(gminaIndex.distToRoute(x, route), gminaIndex.distToRoute(y, route)));
            double hav = routeHaversineKm(route);
            double factor = hav > 1 ? ev.effort() / hav : 2.0;
            double est = ev.effort();
            List<SeedSel> added = new ArrayList<>();
            for (UnvisitedArea a : holes) {
                if (est >= hiBand) break;
                double[] epPoint = gminaIndex.deepestInteriorPoint(a.areaId()); // RUNDA 51: najgłębszy
                if (epPoint == null) epPoint = sampleNearestToGeometry(gminaIndex.samplePointsFor(a), null, geom);
                if (epPoint == null) continue;
                SeedSel sel = new SeedSel(a, epPoint, orderKey(epPoint, baseline, baseCum),
                        ENCLOSED_PROTECTED_SCORE, minDistToBaselineKm(epPoint, baseline));
                selected.add(sel);
                added.add(sel);
                est += 2 * gminaIndex.distToRoute(a, route) * factor; // szac. budżet (jak HOLE-FILL v2)
            }
            if (added.isEmpty()) break;
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            Alns2LocalSearch.twoOpt(route);
            // kredyt-verify: niezaliczona (most/wyspa) → retry centroid → usuń
            Set<Integer> vis = gminaIndex.visitedAreaIds(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            for (SeedSel s : new ArrayList<>(added)) {
                if (vis.contains(s.area().areaId())) continue;
                double[] c = new double[]{s.area().lng(), s.area().lat()};
                swapEntryPoint(selected, s.point(), c, baseline, baseCum);
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            Alns2LocalSearch.twoOpt(route);
            vis = gminaIndex.visitedAreaIds(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            for (SeedSel s : new ArrayList<>(added)) {
                if (!vis.contains(s.area().areaId())) { selected.remove(s); added.remove(s); }
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            Alns2LocalSearch.twoOpt(route);
            filled += added.size();
        }
        return filled;
    }

    /**
     * Weryfikacja kredytu wstawek grow-near (po areaId): gmina wstawki NIEzaliczona →
     * 1. raz podmień entry na centroid (najgłębiej), 2. raz usuń wstawkę. Zwraca true gdy
     * coś zmieniono (caller robi rebuild+2opt+pomiar). Wzorzec „wp 206/Kozłowo".
     */
    private boolean verifyInsertCredit(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                                       List<double[]> baseline, double[] baseCum, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter,
                                       Set<Integer> myInsertAreas, Set<Integer> retriedCentroid,
                                       Set<Integer> visited) {
        boolean changedAny = false;
        for (SeedSel s : new ArrayList<>(selected)) {
            int aid = s.area().areaId();
            if (!myInsertAreas.contains(aid) || s.score() != 0.0 || visited.contains(aid)) continue;
            if (retriedCentroid.add(aid)) {
                double[] centroid = new double[]{s.area().lng(), s.area().lat()}; // (verifyInsertCredit nie ma gminaIndex; deep robi insert wyżej)
                swapEntryPoint(selected, s.point(), centroid, baseline, baseCum);
            } else {
                selected.remove(s);
                myInsertAreas.remove(aid);
            }
            changedAny = true;
        }
        if (changedAny) {
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "verify-insert-credit");
        }
        return changedAny;
    }

    /**
     * Kotwica dla gminy {@code g} na INNYM legu trasy niż spur kandydata (v3.11). Split lega
     * w środku przejścia przez gminę (cięcie cache'owanej geometrii — zero BRouter), SeedSel
     * gminy przenoszony na kotwicę ({@code swapEntryPoint}), crossCount aktualizowany.
     * Zwraca zaktualizowany indeks kandydata (split przed nim przesuwa go o 1) albo -1, gdy
     * żaden leg nie dał się sensownie podzielić — wtedy spur zostawiamy w spokoju.
     */
    private int reanchorOnOtherLeg(List<double[]> route, List<SeedSel> selected, List<double[]> baseline,
                                   double[] baseCum, EdgeCache cache,
                                   BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                   String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                   Map<String, Set<Integer>> edgeCrossCache, Map<Integer, Integer> crossCount,
                                   Set<String> pendingKeys, double[] cur, int idx, UnvisitedArea g) {
        int gid = g.areaId();
        // v3.12: wybierz leg z NAJDŁUŻSZYM przejściem przez gminę (główny korytarz), nie pierwszy
        // z brzegu — kotwica wbita w przelotny skrawek na CUDZYM ogonku robiła z niego łańcuszek,
        // którego pojedyncze cięcia już nie rozplączą (wzorzec Zakroczym/Głusk).
        int bestJ = -1;
        int bestSplit = -1;
        double bestRunKm = -1;
        for (int j = 0; j < route.size() - 1; j++) {
            if (j == idx - 1 || j == idx) continue; // pomiń legi samego spuru
            double[] eA = route.get(j);
            double[] eB = route.get(j + 1);
            if (pendingKeys.contains(edgeKey(eA, eB))) continue; // pending — geometria nieznana
            if (!edgeCrossings(eA, eB, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)
                    .contains(gid)) continue;
            EdgeCache.EdgeInfo eAB = getEdge(eA, eB, cache, brouter, profile, alphaKmPerMeter);
            int aIdx = midpointOfCrossing(eAB.geometry(), g);
            if (aIdx <= 0 || aIdx >= eAB.geometry().size() - 1) continue;
            double runKm = crossingRunKm(eAB.geometry(), g);
            if (runKm > bestRunKm) { bestRunKm = runKm; bestJ = j; bestSplit = aIdx; }
        }
        if (bestJ < 0) return -1;
        double[] eA = route.get(bestJ);
        double[] eB = route.get(bestJ + 1);
        EdgeCache.EdgeInfo eAB = getEdge(eA, eB, cache, brouter, profile, alphaKmPerMeter);
        double[] anchorPt = eAB.geometry().get(bestSplit).clone();
        seedSlicedEdges(cache, eAB, eA, eB, bestSplit, alphaKmPerMeter);
        swapEntryPoint(selected, cur, anchorPt, baseline, baseCum); // SeedSel gminy → kotwica
        route.add(bestJ + 1, anchorPt);
        for (int a : edgeCrossings(eA, eB, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
            crossCount.merge(a, -1, Integer::sum);
        }
        for (int a : edgeCrossings(eA, anchorPt, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
            crossCount.merge(a, 1, Integer::sum);
        }
        for (int a : edgeCrossings(anchorPt, eB, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
            crossCount.merge(a, 1, Integer::sum);
        }
        return bestJ + 1 <= idx ? idx + 1 : idx; // wstawka przed kandydatem przesuwa jego indeks
    }

    /** Kotwica + NOWY SeedSel dla gminy na pierwszym legu z najdłuższym przejściem (split, 0 calli). */
    private boolean anchorAreaNewSel(List<double[]> route, List<SeedSel> selected, List<double[]> baseline,
                                     double[] baseCum, EdgeCache cache,
                                     BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                     String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                     Map<String, Set<Integer>> edgeCrossCache, UnvisitedArea area) {
        int bestJ = -1;
        int bestSplit = -1;
        double bestRun = -1;
        for (int j = 0; j < route.size() - 1; j++) {
            if (!edgeCrossings(route.get(j), route.get(j + 1), edgeCrossCache, cache, gminaIndex,
                    brouter, profile, alphaKmPerMeter).contains(area.areaId())) continue;
            EdgeCache.EdgeInfo e = getEdge(route.get(j), route.get(j + 1), cache, brouter, profile, alphaKmPerMeter);
            int sIdx = midpointOfCrossing(e.geometry(), area);
            if (sIdx <= 0 || sIdx >= e.geometry().size() - 1) continue;
            double run = crossingRunKm(e.geometry(), area);
            if (run > bestRun) { bestRun = run; bestJ = j; bestSplit = sIdx; }
        }
        if (bestJ < 0) return false;
        EdgeCache.EdgeInfo e = getEdge(route.get(bestJ), route.get(bestJ + 1), cache, brouter, profile, alphaKmPerMeter);
        double[] anchorPt = e.geometry().get(bestSplit).clone();
        seedSlicedEdges(cache, e, route.get(bestJ), route.get(bestJ + 1), bestSplit, alphaKmPerMeter);
        route.add(bestJ + 1, anchorPt);
        selected.add(new SeedSel(area, anchorPt, orderKey(anchorPt, baseline, baseCum), 0.0,
                minDistToBaselineKm(anchorPt, baseline)));
        return true;
    }

    /** Wynik rozwiązywania łańcuszków: ile okien rozwiązano + ile dokładnych pytań zadano. */
    private record ChainResult(int resolved, int asked) {}

    /**
     * ŁAŃCUSZKI (v3.12, wzorzec Zakroczym/Głusk): spur z ≥2 punktami (np. kotwica wbita w środku
     * ogonka + tip) jest NIEWIDZIALNY dla cięć pojedynczych — usunięcie tipa zostawia kotwicę,
     * a usunięcie samej kotwicy nie skraca drogi (scalenie = ta sama szosa). Okno k=2..3 KOLEJNYCH
     * punktów traktujemy jak jeden ogonek: jedno dokładne pytanie o prostą prev→next z pominięciem
     * CAŁEGO okna; gminy okna lądują kotwicami na prostej/innych legach, a gdy któraś wypada —
     * całe okno zamienia się w JEDEN krótki wypad (retarget). Max 6 okien na wywołanie.
     */
    private ChainResult chainSpurResolve(List<double[]> route, List<SeedSel> selected, List<double[]> anchors,
                                         List<double[]> baseline, double[] baseCum, EdgeCache cache,
                                         BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                         String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                         java.util.function.Function<double[], UnvisitedArea> findGminaCached,
                                         Map<Integer, UnvisitedArea> idToArea,
                                         Map<String, Set<Integer>> edgeCrossCache) {
        int resolved = 0;
        int asked = 0;
        // v3.13 (fuszerka v3.12: 767 pytań na 12 rozwiązań — restart skanu od zera po każdej
        // zmianie i ponowne pytania o odrzucone okna): zbierz WSZYSTKIE okna RAZ, posortuj po
        // garbie MALEJĄCO (Dzierzgowo/Drzewica/Leoncin pierwsze), jeden przebieg, memo pytanych
        // par (nie pytaj 2. raz), cap 12 rozwiązań.
        record Win(double[] firstPt, int k, double proxy) {}
        List<Win> wins = new ArrayList<>();
        for (int k = 2; k <= 3; k++) {
            for (int i = 1; i + k - 1 < route.size() - 1; i++) {
                boolean hasAnchor = false;
                for (int w = i; w < i + k; w++) {
                    if (isAnchor(route.get(w), anchors)) { hasAnchor = true; break; }
                }
                if (hasAnchor) continue;
                double[] prev = route.get(i - 1);
                double[] next = route.get(i + k);
                double legsKm = 0;
                for (int w = i - 1; w < i + k; w++) {
                    legsKm += getEdge(route.get(w), route.get(w + 1), cache, brouter, profile, alphaKmPerMeter).distanceKm();
                }
                double proxy = legsKm - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
                if (proxy < 4.0) continue; // darmowy filtr: okno nie nadkłada
                boolean sticksOut = false;
                for (int w = i; w < i + k; w++) {
                    if (pointToSegmentKm(route.get(w), prev, next) >= TAIL_MIN_LATERAL_KM) { sticksOut = true; break; }
                }
                if (!sticksOut) continue; // kręty leg, nie ogonek
                wins.add(new Win(route.get(i), k, proxy));
            }
        }
        wins.sort((x, y) -> Double.compare(y.proxy(), x.proxy()));
        Set<String> askedKeys = new HashSet<>(); // memo: o tę scaloną parę już pytano
        Map<Integer, Integer> crossCount = new HashMap<>();
        for (int i = 0; i < route.size() - 1; i++) {
            for (int a : edgeCrossings(route.get(i), route.get(i + 1), edgeCrossCache, cache, gminaIndex,
                    brouter, profile, alphaKmPerMeter)) {
                crossCount.merge(a, 1, Integer::sum);
            }
        }
        for (Win win : wins) {
            if (resolved >= 12) break;
            // Re-lokacja okna po mutacjach (referencja pierwszego punktu, jak w pętli kandydatów).
            int i = -1;
            for (int p = 1; p < route.size() - 1; p++) {
                if (route.get(p) == win.firstPt()) { i = p; break; }
            }
            if (i < 0 || i + win.k() - 1 >= route.size() - 1) continue; // okno rozjechane przez wcześniejsze zmiany
            int k = win.k();
            {
                    double[] prev = route.get(i - 1);
                    double[] next = route.get(i + k);
                    if (!askedKeys.add(edgeKey(prev, next))) continue; // już pytane — nie marnuj calla
                    double legsKm = 0;
                    double legsEffort = 0;
                    for (int w = i - 1; w < i + k; w++) {
                        EdgeCache.EdgeInfo e = getEdge(route.get(w), route.get(w + 1), cache, brouter, profile, alphaKmPerMeter);
                        legsKm += e.distanceKm();
                        legsEffort += e.effort();
                    }
                    EdgeCache.EdgeInfo eDir = getEdge(prev, next, cache, brouter, profile, alphaKmPerMeter);
                    asked++;
                    double realDetour = legsKm - eDir.distanceKm();
                    if (realDetour < 3.0) continue;
                    Set<Integer> crDir = edgeCrossings(prev, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                    Map<Integer, Integer> windowContrib = new HashMap<>();
                    for (int w = i - 1; w < i + k; w++) {
                        for (int a : edgeCrossings(route.get(w), route.get(w + 1), edgeCrossCache, cache, gminaIndex,
                                brouter, profile, alphaKmPerMeter)) {
                            windowContrib.merge(a, 1, Integer::sum);
                        }
                    }
                    Set<Integer> lost = new HashSet<>();
                    for (Map.Entry<Integer, Integer> en : windowContrib.entrySet()) {
                        if (crossCount.getOrDefault(en.getKey(), 0) <= en.getValue() && !crDir.contains(en.getKey())) {
                            lost.add(en.getKey());
                        }
                    }
                    List<double[]> windowPts = new ArrayList<>();
                    for (int w = i; w < i + k; w++) windowPts.add(route.get(w));

                    if (lost.isEmpty()) {
                        // Nic nie wypada: wytnij okno (prosta już policzona), gminy okna kotwicz
                        // na prostej/innych legach (najdłuższe przejście), sieroty bez kredytu precz.
                        for (int w = i + k - 1; w >= i; w--) route.remove(w);
                        for (double[] wpt : windowPts) {
                            UnvisitedArea og = findGminaCached.apply(wpt);
                            if (og == null) { selected.removeIf(s -> s.point() == wpt); continue; }
                            int r = reanchorOnOtherLeg(route, selected, baseline, baseCum, cache, brouter,
                                    profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount,
                                    java.util.Set.of(), wpt, -5, og);
                            if (r < 0) selected.removeIf(s -> s.point() == wpt);
                        }
                        resolved++;
                        crossCount.clear();
                        for (int p2 = 0; p2 < route.size() - 1; p2++) {
                            for (int a : edgeCrossings(route.get(p2), route.get(p2 + 1), edgeCrossCache, cache,
                                    gminaIndex, brouter, profile, alphaKmPerMeter)) {
                                crossCount.merge(a, 1, Integer::sum);
                            }
                        }
                        continue;
                    }
                    // RETARGET okna → JEDEN krótki wypad kryjący wszystko, co okno trzymało.
                    record RtCand(double[] pt, UnvisitedArea owner) {}
                    List<RtCand> rtCands = new ArrayList<>();
                    for (int aid : lost) {
                        UnvisitedArea la = idToArea.get(aid);
                        if (la == null) continue;
                        for (double[] sp : gminaIndex.samplePointsFor(la)) rtCands.add(new RtCand(sp, la));
                        for (double[] gp : gminaIndex.grazePointsFor(la)) rtCands.add(new RtCand(gp, la));
                    }
                    rtCands.sort((x, y) -> Double.compare(
                            velomarker.service.planning.WaypointSelector.haversineKm(prev, x.pt())
                                    + velomarker.service.planning.WaypointSelector.haversineKm(x.pt(), next),
                            velomarker.service.planning.WaypointSelector.haversineKm(prev, y.pt())
                                    + velomarker.service.planning.WaypointSelector.haversineKm(y.pt(), next)));
                    int tried = 0;
                    for (RtCand rc : rtCands) {
                        if (tried >= 2) break;
                        double[] cnd = rc.pt().clone();
                        double gainMax = legsEffort
                                - velomarker.service.planning.WaypointSelector.haversineKm(prev, cnd)
                                - velomarker.service.planning.WaypointSelector.haversineKm(cnd, next);
                        if (gainMax <= 1.0) continue; // linijka
                        tried++;
                        EdgeCache.EdgeInfo nIn = getEdge(prev, cnd, cache, brouter, profile, alphaKmPerMeter);
                        EdgeCache.EdgeInfo nOut = getEdge(cnd, next, cache, brouter, profile, alphaKmPerMeter);
                        double gain = legsEffort - nIn.effort() - nOut.effort();
                        if (gain <= 1.0) continue;
                        Set<Integer> crNIn = edgeCrossings(prev, cnd, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                        Set<Integer> crNOut = edgeCrossings(cnd, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
                        boolean keepsAll = true;
                        for (int aid : lost) {
                            if (!crNIn.contains(aid) && !crNOut.contains(aid)) { keepsAll = false; break; }
                        }
                        if (!keepsAll) continue;
                        // wytnij okno, wstaw nowy punkt; gminy okna i pozostałe lost → kotwice/sele
                        for (int w = i + k - 1; w >= i; w--) route.remove(w);
                        route.add(i, cnd);
                        for (double[] wpt : windowPts) {
                            UnvisitedArea og = findGminaCached.apply(wpt);
                            if (og == null) { selected.removeIf(s -> s.point() == wpt); continue; }
                            int r = reanchorOnOtherLeg(route, selected, baseline, baseCum, cache, brouter,
                                    profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, crossCount,
                                    java.util.Set.of(), wpt, -5, og);
                            if (r < 0) selected.removeIf(s -> s.point() == wpt);
                        }
                        selected.add(new SeedSel(rc.owner(), cnd, orderKey(cnd, baseline, baseCum), 0.0,
                                minDistToBaselineKm(cnd, baseline)));
                        for (int aid : lost) {
                            if (aid == rc.owner().areaId()) continue;
                            UnvisitedArea la = idToArea.get(aid);
                            if (la == null) continue;
                            boolean hasSel = false;
                            for (SeedSel s : selected) {
                                if (s.area().areaId() == aid) { hasSel = true; break; }
                            }
                            if (!hasSel) {
                                anchorAreaNewSel(route, selected, baseline, baseCum, cache, brouter,
                                        profile, alphaKmPerMeter, gminaIndex, edgeCrossCache, la);
                            }
                        }
                        resolved++;
                        crossCount.clear();
                        for (int p2 = 0; p2 < route.size() - 1; p2++) {
                            for (int a : edgeCrossings(route.get(p2), route.get(p2 + 1), edgeCrossCache, cache,
                                    gminaIndex, brouter, profile, alphaKmPerMeter)) {
                                crossCount.merge(a, 1, Integer::sum);
                            }
                        }
                        break;
                    }
            }
        }
        return new ChainResult(resolved, asked);
    }

    /**
     * Przestawienie punktu na środek NAJDŁUŻSZEGO przejścia jego gminy po WŁASNYM legu — dojeździe
     * lub powrocie (v3.13, uwaga usera: „Czosnów też powinien coś mieć u siebie na śladzie",
     * „ślad idzie przez Drzewicę, więc można punkt przestawić"). Strona z przejściem = cięcie
     * z pamięci; druga noga = 1 pytanie. Wołane przy exclusive-empty (nic do stracenia) — akceptacja
     * tylko, gdy nie pogarsza (gain ≥ 0). Zwraca true po udanym przestawieniu.
     */
    private boolean relocateOntoOwnLeg(List<double[]> route, List<SeedSel> selected, List<double[]> baseline,
                                       double[] baseCum, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                       Map<String, Set<Integer>> edgeCrossCache, Map<Integer, Integer> crossCount,
                                       double[] prev, double[] cur, double[] next, int idx, UnvisitedArea g) {
        EdgeCache.EdgeInfo eIn = getEdge(prev, cur, cache, brouter, profile, alphaKmPerMeter);
        EdgeCache.EdgeInfo eOut = getEdge(cur, next, cache, brouter, profile, alphaKmPerMeter);
        double runIn = crossingRunKm(eIn.geometry(), g);
        double runOut = crossingRunKm(eOut.geometry(), g);
        if (Math.max(runIn, runOut) < 0.4) return false; // brak sensownego przejścia na własnych nogach
        boolean inSide = runIn >= runOut;
        EdgeCache.EdgeInfo base = inSide ? eIn : eOut;
        int sIdx = midpointOfCrossing(base.geometry(), g);
        if (sIdx <= 0 || sIdx >= base.geometry().size() - 1) return false;
        double[] newWp = base.geometry().get(sIdx).clone();
        if (velomarker.service.planning.WaypointSelector.haversineKm(newWp, cur) < 0.15) return false;
        EdgeCache.EdgeInfo nIn;
        EdgeCache.EdgeInfo nOut;
        if (inSide) {
            seedSlicedEdges(cache, eIn, prev, cur, sIdx, alphaKmPerMeter); // dojazd: nożyczki
            nIn = getEdge(prev, newWp, cache, brouter, profile, alphaKmPerMeter);  // cache-hit
            nOut = getEdge(newWp, next, cache, brouter, profile, alphaKmPerMeter); // 1 pytanie
        } else {
            seedSlicedEdges(cache, eOut, cur, next, sIdx, alphaKmPerMeter); // powrót: nożyczki
            nOut = getEdge(newWp, next, cache, brouter, profile, alphaKmPerMeter); // cache-hit
            nIn = getEdge(prev, newWp, cache, brouter, profile, alphaKmPerMeter);  // 1 pytanie
        }
        if (eIn.effort() + eOut.effort() - nIn.effort() - nOut.effort() < 0) return false; // nie pogarszaj
        Set<Integer> crIn = edgeCrossings(prev, cur, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
        Set<Integer> crOut = edgeCrossings(cur, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter);
        swapEntryPoint(selected, cur, newWp, baseline, baseCum);
        route.set(idx, newWp);
        for (int a : crIn) crossCount.merge(a, -1, Integer::sum);
        for (int a : crOut) crossCount.merge(a, -1, Integer::sum);
        for (int a : edgeCrossings(prev, newWp, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
            crossCount.merge(a, 1, Integer::sum);
        }
        for (int a : edgeCrossings(newWp, next, edgeCrossCache, cache, gminaIndex, brouter, profile, alphaKmPerMeter)) {
            crossCount.merge(a, 1, Integer::sum);
        }
        return true;
    }

    /** v3.19: wynik relokacji odroczonej — kredyt obu nowych nóg (B2) + ewentualna noga powrotna do batcha. */
    private record RelocResult(boolean ok, Set<Integer> newInCredit, Set<Integer> newOutCredit, double[][] pendingDeparture) {
        static RelocResult fail() { return new RelocResult(false, null, null, null); }
    }

    /**
     * RELOKACJA v3.19 — DECYZJA W JTS, noga powrotna ODROCZONA do batcha. Spłyca spur JEDYNY-KONTAKT na
     * granicę gminy: najpłytszy wierzchołek własnej nogi kredytujący WSZYSTKIE {@code exclusive}
     * ({@link #shallowestCoveringVertex}, 0 calli), slice dojazdu (0 calli), stawia waypoint. BEZ
     * effort-checku (spłycenie ZAWSZE skraca i zachowuje kredyt — dojazd-slice pokrywa exclusive z
     * konstrukcji) i BEZ routowania nogi powrotnej: tam-i-z-powrotem = slice (0 calli), loop-spur = noga
     * PENDING (caller batchuje przez {@link #prewarmPairs} — 1 równoległy strzał/pass zamiast per-tail =
     * koniec 838 strzałów). Zwraca kredyt obu nóg (B2 legGminas; pending-loop noga powrotna = estymata
     * {@code {g0}}, liczona dokładnie w kolejnym passie z cache po batchu). Mutuje route+selected.
     */
    private RelocResult relocateShallowDeferred(List<double[]> route, List<SeedSel> selected, List<double[]> baseline,
                                                double[] baseCum, EdgeCache cache,
                                                BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                                String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                                Set<Integer> exclusive, double[] prev, double[] cur, double[] next,
                                                int idx, UnvisitedArea g, EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut,
                                                boolean allowReroute) {
        if (exclusive.isEmpty()) return RelocResult.fail();
        // RUNDA 59: guard RUNDA 39 (cur kredytuje −200 → fail) USUNIĘTY. Reguła 2b: jedyny-wjazd ma być DOKŁADNIE 220m —
        // za-głęboki (kredytuje −200, ale >220m) trzeba SKRÓCIĆ do 220m. Cel = firstBufferEntryPoints (220m); gdy cur już
        // ~220m → newWp≈cur → `haversineKm(newWp,cur)<0.15` (niżej) = no-op → ZOSTAW (bez churnu). Wołane TYLKO dla zaułków.
        for (int side = 0; side < 2; side++) {
            boolean inSide = side == 0;
            EdgeCache.EdgeInfo own = inSide ? eIn : eOut;
            List<double[]> walk;
            if (inSide) {
                walk = own.geometry();
            } else {
                walk = new ArrayList<>(own.geometry());
                java.util.Collections.reverse(walk);
            }
            // RUNDA 54: cel −220 (jak anchor/D-slice), NIE −200. RUNDA 39 pchała do pierwszego wierzchołka w buforze
            // −200 (≈200m) → ślad sięgał tylko 200m → w cyklu N+1 anchor (próg −220) widział muśnięcie → centroid (#117).
            // firstBufferEntryPoints zwraca punkt 220m w głąb g NA tej nodze (ta sama maszyneria co anchor); bierzemy
            // wierzchołek najbliższy → wp ~220m. null = noga nie wchodzi ≥220m w g → ta strona odpada.
            double[] deep = gminaIndex.firstBufferEntryPoints(walk).get(g.areaId());
            if (deep == null) continue;
            // RUNDA 67: wp = DOKŁADNY punkt przecięcia śladu z buforem −220 (firstBufferEntryPoints, TEN SAM co anchor),
            // NIE najbliższy wierzchołek (snap lądował POZA −220 = płytko). Slice własnej nogi W SEGMENCIE zawierającym deep.
            double[] newWp = deep.clone();
            // RUNDA 68: wp JUŻ na 220m na TEJ stronie → ZOSTAW (return fail), NIE przerzucaj na drugą (wyjazd). Inaczej
            // #169 Mława (na wjeździe) przeskakiwał na #170 (wyjazd). Genuine deep zaułek: cur głęboki ≠ cel → nie no-op.
            if (velomarker.service.planning.WaypointSelector.haversineKm(newWp, cur) < 0.15) return RelocResult.fail();
            List<double[]> ownGeom = own.geometry();
            int segOwn = -1; double bestSegSD = Double.MAX_VALUE;
            for (int m = 0; m < ownGeom.size() - 1; m++) {
                double sd = pointToSegmentExactKm(newWp, ownGeom.get(m), ownGeom.get(m + 1));
                if (sd < bestSegSD) { bestSegSD = sd; segOwn = m; }
            }
            if (segOwn < 0) continue;
            Set<Integer> newInCredit;
            Set<Integer> newOutCredit;
            double[][] pendingDeparture;
            if (inSide) {
                seedSlicedEdgesAtPoint(cache, eIn, prev, cur, segOwn, newWp, alphaKmPerMeter); // prev→newWp DOKŁADNY punkt (0 calli)
                newInCredit = gminaIndex.visitedAreaIds(
                        getEdge(prev, newWp, cache, brouter, profile, alphaKmPerMeter).geometry()); // cache-hit
                EdgeCache.EdgeInfo dep = sliceDepart(cache, eOut, cur, next, newWp, alphaKmPerMeter, true);
                if (dep != null) {                                              // tam-i-z-powrotem = slice (0 calli)
                    newOutCredit = gminaIndex.visitedAreaIds(dep.geometry());
                    pendingDeparture = null;
                } else if (allowReroute) {                                      // v3.30 (Q2): loop → REROUTE nogi powrotnej
                    newOutCredit = Set.of(g.areaId());                          // (1 strzał, bounded+stay przez caller)
                    pendingDeparture = new double[][]{newWp, next};
                } else continue;                                               // cap przekroczony → slice-only, fail
            } else {
                seedSlicedEdgesAtPoint(cache, eOut, cur, next, segOwn, newWp, alphaKmPerMeter); // newWp→next DOKŁADNY punkt (0 calli)
                newOutCredit = gminaIndex.visitedAreaIds(
                        getEdge(newWp, next, cache, brouter, profile, alphaKmPerMeter).geometry()); // cache-hit
                EdgeCache.EdgeInfo dep = sliceDepart(cache, eIn, prev, cur, newWp, alphaKmPerMeter, false);
                if (dep != null) {
                    newInCredit = gminaIndex.visitedAreaIds(dep.geometry());
                    pendingDeparture = null;
                } else if (allowReroute) {                                      // v3.30 (Q2): loop → REROUTE dojazdu (#111/#32)
                    newInCredit = Set.of(g.areaId());
                    pendingDeparture = new double[][]{prev, newWp};
                } else continue;
            }
            swapEntryPoint(selected, cur, newWp, baseline, baseCum);
            route.set(idx, newWp);
            return new RelocResult(true, newInCredit, newOutCredit, pendingDeparture);
        }
        return RelocResult.fail();
    }

    /**
     * RELOKACJA v3.17a — JTS-CLEAN (dokończenie refaktoru v3.15). Spłyca deep-spur: stawia waypoint
     * na NAJPŁYTSZYM wierzchołku własnej nogi, którego prefiks kredytuje WSZYSTKIE {@code exclusive}
     * (binary search po prefiksach — monotonia: dłuższy prefiks = nie mniej gmin; kryterium kredytu =
     * port JTS {@code visitedAreaIds}). Koniec ręcznego {@code walkInsideFromBoundary}/{@code pointInArea},
     * które rozjeżdżały się z oracle'em kredytu → odmowy „głęboka-jedyna". Noga „własna" = SLICE geometrii
     * (0 calli); druga noga: tam-i-z-powrotem (snap ≤{@value #SLICE_SNAP_KM} km) = SLICE (0 calli),
     * loop-spur = 1 strzał (ACCEPT — punkt jest kredytowo poprawny z konstrukcji). Próbuje od strony
     * dojazdu (eIn) i odjazdu (eOut). Mutuje route+selected; pass-level count aktualizuje caller (B2).
     */
    private boolean relocateShallowOwnLeg(List<double[]> route, List<SeedSel> selected, List<double[]> baseline,
                                          double[] baseCum, EdgeCache cache,
                                          BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                          String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                          Map<String, Set<Integer>> edgeCrossCache, Map<Integer, Integer> crossCount,
                                          Set<Integer> exclusive, double[] prev, double[] cur, double[] next,
                                          int idx, UnvisitedArea g, EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
        if (exclusive.isEmpty()) return false;
        for (int side = 0; side < 2; side++) {
            boolean inSide = side == 0;                    // true = spłycenie od strony eIn (dojazd)
            EdgeCache.EdgeInfo own = inSide ? eIn : eOut;
            // prefiks liczymy od anchora tej nogi: eIn od prev (forward), eOut od next (reversed)
            List<double[]> walk;
            if (inSide) {
                walk = own.geometry();
            } else {
                walk = new ArrayList<>(own.geometry());
                java.util.Collections.reverse(walk);
            }
            int kw = shallowestCoveringVertex(walk, exclusive, gminaIndex);
            if (kw <= 0 || kw >= walk.size() - 1) continue;
            double[] newWp = walk.get(kw).clone();
            if (velomarker.service.planning.WaypointSelector.haversineKm(newWp, cur) < 0.15) continue; // już płytko
            int kOwn = inSide ? kw : own.geometry().size() - 1 - kw; // indeks w oryginalnej (nie-reversed) geometrii
            EdgeCache.EdgeInfo nIn;
            EdgeCache.EdgeInfo nOut;
            if (inSide) {
                seedSlicedEdges(cache, eIn, prev, cur, kOwn, alphaKmPerMeter);          // prev→newWp = slice (0 calli)
                nIn = getEdge(prev, newWp, cache, brouter, profile, alphaKmPerMeter);   // cache-hit
                EdgeCache.EdgeInfo dep = sliceDepart(cache, eOut, cur, next, newWp, alphaKmPerMeter, true);
                nOut = dep != null ? dep                                                // tam-i-z-powrotem (0 calli)
                        : getEdge(newWp, next, cache, brouter, profile, alphaKmPerMeter); // loop: 1 strzał
            } else {
                seedSlicedEdges(cache, eOut, cur, next, kOwn, alphaKmPerMeter);         // newWp→next = slice (0 calli)
                nOut = getEdge(newWp, next, cache, brouter, profile, alphaKmPerMeter);  // cache-hit
                EdgeCache.EdgeInfo dep = sliceDepart(cache, eIn, prev, cur, newWp, alphaKmPerMeter, false);
                nIn = dep != null ? dep
                        : getEdge(prev, newWp, cache, brouter, profile, alphaKmPerMeter); // loop: 1 strzał
            }
            if (eIn.effort() + eOut.effort() - nIn.effort() - nOut.effort() < 0) continue; // nie pogarszaj
            Set<Integer> credit = new HashSet<>(gminaIndex.visitedAreaIds(nIn.geometry()));
            credit.addAll(gminaIndex.visitedAreaIds(nOut.geometry()));
            if (!credit.containsAll(exclusive)) continue;  // sanity (z konstrukcji powinno trzymać)
            swapEntryPoint(selected, cur, newWp, baseline, baseCum);
            route.set(idx, newWp);
            return true;
        }
        return false;
    }

    /** v3.22: indeks punktu w route po TOŻSAMOŚCI (==), odporny na przesunięcia po delete/insert. -1 gdy brak. */
    private static int identityIndexOf(List<double[]> route, double[] p) {
        for (int i = 0; i < route.size(); i++) if (route.get(i) == p) return i;
        return -1;
    }

    /**
     * Najpłytszy wierzchołek k (>0), dla którego prefiks {@code geom[0..k]} kredytuje WSZYSTKIE
     * {@code need} (kryterium kredytu = port JTS {@code visitedAreaIds}). Monotonia (dłuższy prefiks
     * to nadzbiór segmentów = nie mniej kredytowanych gmin) → binary search, ~log(n) wywołań JTS,
     * 0 BRouter. -1 gdy pełny leg nie pokrywa {@code need}.
     */
    private static int shallowestCoveringVertex(List<double[]> geom, Set<Integer> need, GminaIndex idx) {
        if (need.isEmpty() || geom.size() < 3) return -1;
        if (!idx.visitedAreaIds(geom).containsAll(need)) return -1; // pełny leg nie pokrywa
        int lo = 1, hi = geom.size() - 2, ans = geom.size() - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idx.visitedAreaIds(geom.subList(0, mid + 1)).containsAll(need)) { ans = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return ans;
    }

    /**
     * v3.24 GEOMETRIA „kutas": wykrywa OUT-AND-BACK — eOut wraca TĄ SAMĄ drogą co eIn (ślad wystaje
     * z linii i wraca). {@code eIn}=[prev..cur], {@code eOut}=[cur..next], cur wspólny. Liczy m = długość
     * retrace'u (eOut[k] ≈ eIn[koniec-k] dla k=1..m, tol {@value #RETRACE_TOL_KM} km). Zwraca punkt
     * ROZEJŚCIA D = eIn[koniec-m] = eOut[m] (gdzie linia przestaje wracać po sobie = ciągły ślad). D jest
     * wierzchołkiem OBU nóg → slice obu = 0 BRouter. null = brak retrace (nie there-and-back / loop).
     */
    private double[] outAndBackDivergence(EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
        List<double[]> gi = eIn.geometry();
        List<double[]> go = eOut.geometry();
        int ni = gi.size(), no = go.size();
        if (ni < 3 || no < 3) return null;
        int m = 0;
        while (m + 1 <= ni - 2 && m + 1 <= no - 2
                && velomarker.service.planning.WaypointSelector.haversineKm(gi.get(ni - 2 - m), go.get(m + 1)) <= RETRACE_TOL_KM) {
            m++;
        }
        if (m == 0) return null;
        return gi.get(ni - 1 - m).clone();
    }

    /**
     * Buduje EdgeInfo nogi od/do {@code newWp} wzdłuż geometrii {@code full} (a→b), gdy newWp snapuje
     * się do wierzchołka full (tam-i-z-powrotem, ≤{@value #SLICE_SNAP_KM} km). {@code forward}=true →
     * newWp→b (ogon full[m..]); false → a→newWp (głowa full[..m]). Dystans/wznios proporcjonalnie po
     * haversine. Seeduje wynik do cache (właściwy klucz). null = brak snapu (loop-spur). 0 BRouter.
     */
    private EdgeCache.EdgeInfo sliceDepart(EdgeCache cache, EdgeCache.EdgeInfo full, double[] a, double[] b,
                                           double[] newWp, double alphaKmPerMeter, boolean forward) {
        List<double[]> geom = full.geometry();
        int m = nearestVertexIdx(geom, newWp);
        if (m <= 0 || m >= geom.size() - 1) return null;
        if (velomarker.service.planning.WaypointSelector.haversineKm(geom.get(m), newWp) > SLICE_SNAP_KM) return null;
        List<double[]> g2;
        if (forward) {                                   // newWp → b = [newWp] + full[m..end]
            g2 = new ArrayList<>(geom.size() - m + 1);
            g2.add(newWp.clone());
            g2.addAll(geom.subList(m, geom.size()));
        } else {                                         // a → newWp = full[0..m] + [newWp]
            g2 = new ArrayList<>(m + 2);
            g2.addAll(geom.subList(0, m + 1));
            g2.add(newWp.clone());
        }
        double hFull = Math.max(0.001, polyHavKm(geom));
        double h2 = polyHavKm(g2);
        double d2 = full.distanceKm() * (h2 / hFull);
        double c2 = full.climbM() * (h2 / hFull);
        EdgeCache.EdgeInfo e2 = new EdgeCache.EdgeInfo(d2, c2, d2 + alphaKmPerMeter * c2, g2);
        if (forward) cache.getOrCompute(newWp[0], newWp[1], b[0], b[1], pts -> e2);
        else cache.getOrCompute(a[0], a[1], newWp[0], newWp[1], pts -> e2);
        return e2;
    }

    /** Łączna długość (km) NAJDŁUŻSZEGO ciągu wierzchołków geometrii wewnątrz gminy. */
    private static double crossingRunKm(List<double[]> legGeom, UnvisitedArea g) {
        double best = 0;
        double run = 0;
        for (int i = 1; i < legGeom.size(); i++) {
            if (pointInArea(legGeom.get(i), g) && pointInArea(legGeom.get(i - 1), g)) {
                run += velomarker.service.planning.WaypointSelector.haversineKm(legGeom.get(i - 1), legGeom.get(i));
                if (run > best) best = run;
            } else {
                run = 0;
            }
        }
        return best;
    }

    /** Indeks wierzchołka polyline najbliższego punktowi {@code p} (haversine). -1 dla pustej. */
    private static int nearestVertexIdx(List<double[]> geom, double[] p) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < geom.size(); i++) {
            double d = velomarker.service.planning.WaypointSelector.haversineKm(geom.get(i), p);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** Wynik próby skrótu spuru cięciem geometrii (out-and-back): nowy wp + obie krawędzie + zysk. */
    private record SpurSlice(double[] newWp, EdgeCache.EdgeInfo eInNew, EdgeCache.EdgeInfo eOutNew, double gain) {}

    /**
     * MOVE-shorten BEZ BRoutera (v4): jeśli spur to out-and-back po TEJ SAMEJ drodze (punkt cięcia
     * na eIn ma wierzchołek eOut w promieniu {@value #SLICE_SNAP_KM} km), to skrócone krawędzie
     * powstają CIĘCIEM cache'owanych geometrii: eIn[0..k] (dokładne) + newWp⌢eOut[m..koniec]
     * (łącznik ≤50 m). Dystans/wznios proporcjonalnie po haversine względem rodzica.
     * {@code null} gdy: tip płytko / lollipop / loop-spur (dojazd inną drogą niż powrót).
     */
    private static SpurSlice trySliceShorten(EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut,
                                             UnvisitedArea g, double alphaKmPerMeter) {
        double[] probe = walkInsideFromBoundary(eIn.geometry(), g, TAIL_INSIDE_M);
        if (probe == null) return null;
        int k = nearestVertexIdx(eIn.geometry(), probe);
        if (k <= 0 || k >= eIn.geometry().size() - 1) return null;
        double[] newWp = eIn.geometry().get(k).clone();
        if (!pointInArea(newWp, g)) return null; // snap do wierzchołka wypadł poza gminę
        int m = nearestVertexIdx(eOut.geometry(), newWp);
        if (m < 0 || m >= eOut.geometry().size() - 1) return null;
        if (velomarker.service.planning.WaypointSelector.haversineKm(eOut.geometry().get(m), newWp) > SLICE_SNAP_KM) {
            return null; // powrót inną drogą (loop-spur) — to robi wariant BRouterowy
        }
        List<double[]> g1 = new ArrayList<>(eIn.geometry().subList(0, k + 1));
        List<double[]> g2 = new ArrayList<>(eOut.geometry().size() - m + 1);
        g2.add(newWp.clone());
        g2.addAll(eOut.geometry().subList(m, eOut.geometry().size()));
        double hInFull = Math.max(0.001, polyHavKm(eIn.geometry()));
        double hOutFull = Math.max(0.001, polyHavKm(eOut.geometry()));
        double h1 = polyHavKm(g1);
        double h2 = polyHavKm(g2);
        double d1 = eIn.distanceKm() * (h1 / hInFull);
        double c1 = eIn.climbM() * (h1 / hInFull);
        double d2 = eOut.distanceKm() * (h2 / hOutFull);
        double c2 = eOut.climbM() * (h2 / hOutFull);
        EdgeCache.EdgeInfo e1 = new EdgeCache.EdgeInfo(d1, c1, d1 + alphaKmPerMeter * c1, g1);
        EdgeCache.EdgeInfo e2 = new EdgeCache.EdgeInfo(d2, c2, d2 + alphaKmPerMeter * c2, g2);
        double gain = eIn.effort() + eOut.effort() - e1.effort() - e2.effort();
        return new SpurSlice(newWp, e1, e2, gain);
    }

    /** Pozycja najtańszej insercji punktu w trasę (haversine; in-memory). Dla REPAIR re-insert. */
    private static int cheapestInsertPos(List<double[]> route, double[] p) {
        int bestPos = 1;
        double best = Double.MAX_VALUE;
        for (int i = 1; i < route.size(); i++) {
            double cost = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), p)
                    + velomarker.service.planning.WaypointSelector.haversineKm(p, route.get(i))
                    - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i));
            if (cost < best) { best = cost; bestPos = i; }
        }
        return bestPos;
    }

    /**
     * Indeks (środkowy wierzchołek po dystansie) NAJDŁUŻSZEGO ciągu wierzchołków geometrii legu
     * leżących wewnątrz gminy {@code g}. -1 gdy geometria nie wchodzi w gminę. Używany do
     * relokacji/kotwic: punkt NA realnym śladzie w środku przejazdu przez gminę.
     */
    private static int midpointOfCrossing(List<double[]> legGeom, UnvisitedArea g) {
        if (legGeom == null || legGeom.size() < 3) return -1;
        int bestStart = -1;
        int bestEnd = -1;
        double bestLen = -1;
        int runStart = -1;
        double runLen = 0;
        for (int i = 0; i < legGeom.size(); i++) {
            boolean inside = pointInArea(legGeom.get(i), g);
            if (inside) {
                if (runStart < 0) { runStart = i; runLen = 0; }
                else runLen += velomarker.service.planning.WaypointSelector.haversineKm(legGeom.get(i - 1), legGeom.get(i));
            }
            if ((!inside || i == legGeom.size() - 1) && runStart >= 0) {
                int runEnd = inside ? i : i - 1;
                if (runLen > bestLen) { bestLen = runLen; bestStart = runStart; bestEnd = runEnd; }
                runStart = -1;
            }
        }
        if (bestStart < 0) return -1;
        double half = bestLen / 2;
        double acc = 0;
        int mid = bestStart;
        for (int i = bestStart + 1; i <= bestEnd; i++) {
            acc += velomarker.service.planning.WaypointSelector.haversineKm(legGeom.get(i - 1), legGeom.get(i));
            mid = i;
            if (acc >= half) break;
        }
        // Clamp do wnętrza geometrii (split na końcach = degeneracja).
        return Math.max(1, Math.min(mid, legGeom.size() - 2));
    }

    /**
     * v3.30 (Q2 usera „strzel ponownie o cały ślad"): po cięciu spurów przeroutuj REALNIE legi zasilone
     * SLICEM (przybliżenie kształtu starej nogi). Slice trzyma stary detour; realny BRouter daje prawdziwą
     * (często krótszą) geometrię → wtórniaki (resztki po skróceniu) ujawniają się i są docinane w kolejnej
     * rundzie pętli do-skutku. Zwraca liczbę przeroutowanych legów. Mało strzałów (tylko sliced, zbiega).
     */
    private int rerouteApproximateLegs(List<double[]> route, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter) {
        int rerouted = 0;
        cache.setReason("ogonek-relokacja"); // re-route po cięciu = realne strzały tej kategorii
        for (int i = 0; i < route.size() - 1; i++) {
            double[] a = route.get(i), b = route.get(i + 1);
            if (!cache.isApproximate(a[0], a[1], b[0], b[1])) continue;
            EdgeCache.EdgeInfo slice = getEdge(a, b, cache, brouter, profile, alphaKmPerMeter); // slice (hit)
            double hav = velomarker.service.planning.WaypointSelector.haversineKm(a, b);
            // tylko DETOUR-owe slice'y (kształt nadkłada) — proste tam-i-z-powrotem są dokładne, nie strzelaj.
            if (slice.distanceKm() <= 1.3 * Math.max(0.05, hav)) continue;
            cache.invalidate(a[0], a[1], b[0], b[1]);                          // usuń slice
            EdgeCache.EdgeInfo real = getEdge(a, b, cache, brouter, profile, alphaKmPerMeter); // REAL BRouter
            if (real.distanceKm() < 0.97 * slice.distanceKm()) rerouted++;    // realnie krótsza → wtórniak ujawniony
        }
        cache.setReason("pomiar");
        return rerouted;
    }

    /**
     * Tnie EdgeInfo na wierzchołku {@code splitIdx} na dwa sub-edge i SEEDUJE nimi EdgeCache —
     * ZERO BRouter calls. Punkt cięcia leży NA geometrii (droga ta sama), więc slice jest dokładny;
     * distanceKm/climbM dzielone proporcjonalnie po haversine slice'ów.
     */
    private static void seedSlicedEdges(EdgeCache cache, EdgeCache.EdgeInfo full, double[] a, double[] b,
                                        int splitIdx, double alphaKmPerMeter) {
        List<double[]> geom = full.geometry();
        List<double[]> g1 = new ArrayList<>(geom.subList(0, splitIdx + 1));
        List<double[]> g2 = new ArrayList<>(geom.subList(splitIdx, geom.size()));
        double h1 = polyHavKm(g1);
        double h2 = polyHavKm(g2);
        double total = Math.max(0.001, h1 + h2);
        double d1 = full.distanceKm() * (h1 / total);
        double d2 = full.distanceKm() * (h2 / total);
        double c1 = full.climbM() * (h1 / total);
        double c2 = full.climbM() * (h2 / total);
        double[] p = geom.get(splitIdx);
        EdgeCache.EdgeInfo e1 = new EdgeCache.EdgeInfo(d1, c1, d1 + alphaKmPerMeter * c1, g1);
        EdgeCache.EdgeInfo e2 = new EdgeCache.EdgeInfo(d2, c2, d2 + alphaKmPerMeter * c2, g2);
        cache.putApproximate(a[0], a[1], p[0], p[1], e1); // v3.30: slice = przybliżenie → re-route real później
        cache.putApproximate(p[0], p[1], b[0], b[1], e2);
    }

    /** RUNDA 22b: jak {@link #seedSlicedEdges}, ale split w DOWOLNYM punkcie {@code point} leżącym na segmencie
     *  {@code (geom[segIdx], geom[segIdx+1])} — NIE w wierzchołku. Pozwala wstawić wp dokładnie w przecięciu śladu z
     *  buforem (interpolowany punkt), niezależnie od gęstości wierzchołków (długie proste odcinki). */
    private static void seedSlicedEdgesAtPoint(EdgeCache cache, EdgeCache.EdgeInfo full, double[] a, double[] b,
                                               int segIdx, double[] point, double alphaKmPerMeter) {
        List<double[]> geom = full.geometry();
        List<double[]> g1 = new ArrayList<>(geom.subList(0, segIdx + 1));
        g1.add(point.clone());
        List<double[]> g2 = new ArrayList<>();
        g2.add(point.clone());
        g2.addAll(geom.subList(segIdx + 1, geom.size()));
        double h1 = polyHavKm(g1), h2 = polyHavKm(g2);
        double total = Math.max(0.001, h1 + h2);
        double d1 = full.distanceKm() * (h1 / total), d2 = full.distanceKm() * (h2 / total);
        double c1 = full.climbM() * (h1 / total), c2 = full.climbM() * (h2 / total);
        EdgeCache.EdgeInfo e1 = new EdgeCache.EdgeInfo(d1, c1, d1 + alphaKmPerMeter * c1, g1);
        EdgeCache.EdgeInfo e2 = new EdgeCache.EdgeInfo(d2, c2, d2 + alphaKmPerMeter * c2, g2);
        // RUNDA 23: REAL, nie putApproximate — entry leży NA śladzie (slice = podścieżka realnej trasy broutera),
        // więc rerouteApproximateLegs nie ma czego poprawiać. Approx → reroute re-optymalizuje prev→entry świeżo,
        // może zsunąć entry z bufora (gmina spada → churn). getOrCompute cache'uje jako realne (wzorzec sliceDepart).
        cache.getOrCompute(a[0], a[1], point[0], point[1], pts -> e1);
        cache.getOrCompute(point[0], point[1], b[0], b[1], pts -> e2);
    }

    /** Suma haversine po kolejnych wierzchołkach polyline (km). */
    private static double polyHavKm(List<double[]> geom) {
        double sum = 0;
        for (int i = 1; i < geom.size(); i++) {
            sum += velomarker.service.planning.WaypointSelector.haversineKm(geom.get(i - 1), geom.get(i));
        }
        return sum;
    }

    /**
     * RUNDA 24 — ANCHOR-INTERSECTS (główny silnik pokrycia, raz na cykl). RESET wszystkich: dla KAŻDEJ gminy którą
     * ślad DOTYKA (pełny wielokąt, {@code touchedAreaIds} — nawet muśnięcie rogiem) ustawia świeży wp wg reguły:
     * <ul>
     *   <li>ślad wchodzi ≥200m w rdzeń → wp na PIERWSZYM wejściu wzdłuż śladu ({@code firstCreditedCrossing.entry});</li>
     *   <li>tylko muska (nigdzie ≥200m) → wp w CENTROIDZIE gminy ({@code area.lng/lat}) — reroute wepchnie ślad w głąb,
     *       następny cykl go spłyci.</li>
     * </ul>
     * Stare wp-gmin są kasowane i postawione od nowa. Targety liczone na SNAPSHOCIE śladu (przed resetem). Kończy
     * {@code rebuildOrderedRoute + twoOpt} (0 BRouter — realny reroute wchodzi w {@code tailPruneJts} które leci potem).
     */
    private void anchorResetTouched(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
                                    List<double[]> anchors, List<double[]> baseline, double[] baseCum, EdgeCache cache,
                                    BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
                                    double alphaKmPerMeter, GminaIndex gminaIndex, List<UnvisitedArea> pool,
                                    String debugPhase) {
        long t0 = System.nanoTime();
        Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);
        // RUNDA 52: gminy z głębokim (≥220m) start/meta/via — obowiązkowy punkt pokrywa gminę → bez osobnego wp.
        Set<Integer> deepAnchorGminy = new HashSet<>();
        for (double[] an : anchors) {
            UnvisitedArea ag = gminaIndex.findDeeplyCreditedGminaForPoint(an[0], an[1]);
            if (ag != null) deepAnchorGminy.add(ag.areaId());
        }
        // RUNDA 64: PĘTLA DO-SKUTKU. {kotwicz touched → 2opt → REROUTE}. REROUTE potrafi przeprowadzić ślad GŁĘBOKO
        // (≥220m) przez NOWĄ gminę, której PRZED kotwiczeniem nie dotykał (Tomaszów Maz. — przelot po reorderze) → nie
        // miała kotwicy. Powtarzaj aż żadna gmina nie jest wchodzona ≥220m bez kotwicującego wp.
        int iter = 0, onBuffer = 0, onCentroid = 0, touchedCount = 0;
        boolean newDeep = true;
        List<double[]> track = concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter);
        while (newDeep && iter < 5) {
            iter++;
            Map<Integer, double[]> entryMap = gminaIndex.firstBufferEntryPoints(track); // gmina→pierwsze −220 (≥220m)
            Set<Integer> touched = gminaIndex.touchedAreaIds(track);                    // pełny wielokąt (muśnięcia → centroid)
            touchedCount = touched.size();
            List<SeedSel> fresh = new ArrayList<>();
            onBuffer = 0; onCentroid = 0;
            for (int vid : touched) {
                if (deepAnchorGminy.contains(vid)) continue;
                UnvisitedArea a = idToArea.get(vid);
                if (a == null) continue;
                double[] target = entryMap.get(vid);
                if (target != null) { onBuffer++; }                                    // pierwsze −220 (≥220m w głąb)
                else { double[] dp = gminaIndex.deepestInteriorPoint(vid);              // muśnięcie → najgłębszy punkt gminy
                       target = dp != null ? dp : new double[]{a.lng(), a.lat()}; onCentroid++; }
                fresh.add(new SeedSel(a, target, orderKey(target, baseline, baseCum), 0.0, minDistToBaselineKm(target, baseline)));
            }
            // RESET: usuń nie-anchor wp leżące w gminie; postaw świeże; ułóż + 2opt.
            selected.removeIf(s -> !isAnchor(s.point(), anchors)
                    && gminaIndex.findGminaForPoint(s.point()[0], s.point()[1]) != null);
            selected.addAll(fresh);
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "anchor-intersected" + (iter > 1 ? "-i" + iter : ""));
            // REROUTE realny: concatRealGeometry liczy nowe nogi (BRouter) → ujawnia głębokie przeloty nowego porządku.
            track = concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter);
            // KONIEC gdy żadna gmina nie jest wchodzona ≥220m bez kredytującego wp.
            Map<Integer, double[]> deepNow = gminaIndex.firstBufferEntryPoints(track);
            Set<Integer> anchored = new HashSet<>();
            for (SeedSel s : selected) { UnvisitedArea ca = gminaIndex.findCreditedGminaForPoint(s.point()[0], s.point()[1]); if (ca != null) anchored.add(ca.areaId()); }
            for (double[] an : anchors) { UnvisitedArea ca = gminaIndex.findCreditedGminaForPoint(an[0], an[1]); if (ca != null) anchored.add(ca.areaId()); }
            newDeep = false;
            int newDeepCount = 0;
            for (int vid : deepNow.keySet())
                if (!anchored.contains(vid) && !deepAnchorGminy.contains(vid) && idToArea.containsKey(vid)) { newDeep = true; newDeepCount++; }
            log.info("ALNS2 ANCHOR-INTERSECTS [{}] iter {}: touched={}, wejście={} centroid={}, nowe-głębokie-bez-kotwicy={}",
                    new Object[]{debugPhase, iter, touched.size(), onBuffer, onCentroid, newDeepCount});
        }
        log.info("ALNS2 ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iter, touchedCount, (System.nanoTime() - t0) / 1_000_000});
    }

    /** RUNDA 7 (prosto): dla każdej gminy którą ZALICZA cały ślad (intersects buffer(-200)), a która NIE ma wp
     *  FIZYCZNIE w środku — wstaw wp ~200m w głąb od granicy NA ŚLADZIE (creditedCrossing.entry → slice, 0 BRouter).
     *  Wołane PO enclosedFill, PRZED cięciem. Bez detourów/verify: entry leży na śladzie ≥200m w głąb = zalicza. */
    /**
     * Wstawia płytki wp (~200m w głąb, slice na śladzie, 0 BRouter) KAŻDEJ gminie którą ślad ZALICZA, a która
     * nie ma wp w środku. RUNDA 11: {@code restrictTo} (nullable) ogranicza do podanych id (np. gminy właśnie
     * usuniętych spurów — PRZESUŃ), {@code null} = wszystkie. {@code stayOut} (nullable) zbiera dodane wp →
     * caller dorzuca je do {@code stay}, żeby passy ich NIE kasowały z powrotem (koniec ping-pongu anchor↔delete).
     */
    private int anchorIntersectedGminy(List<double[]> route, List<SeedSel> selected, List<double[]> anchors,
                                       List<double[]> baseline, double[] baseCum, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter, GminaIndex gminaIndex,
                                       Set<Integer> restrictTo, Set<double[]> stayOut, String debugPhase) {
        boolean anat = restrictTo == null && debugGeoJson; // RUNDA 21: szczegółowy log ANCHOR-ANATOMIA tylko dla entry-anchora
        List<String> anatAdds = anat ? new ArrayList<>() : null;
        List<String> anatFails = anat ? new ArrayList<>() : null;
        List<String> anatShallow = anat ? new ArrayList<>() : null;
        List<double[]> track = concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter);
        Set<Integer> covered = gminaIndex.visitedAreaIds(track); // gminy które ślad ZALICZA (intersects buffer(-200))
        // RUNDA 16: gminy z KREDYTUJĄCYM wp (findCreditedGminaForPoint — w buforze −200m, NIE pełny wielokąt).
        // Płytki wp przy granicy (w wielokącie, poza buforem) NIE liczy się → gmina dostanie wp na buforze.
        Set<Integer> wpInside = new HashSet<>();
        for (SeedSel s : selected) {
            UnvisitedArea a = gminaIndex.findCreditedGminaForPoint(s.point()[0], s.point()[1]);
            if (a != null) wpInside.add(a.areaId());
        }
        for (double[] a : anchors) {
            UnvisitedArea ga = gminaIndex.findCreditedGminaForPoint(a[0], a[1]);
            if (ga != null) wpInside.add(ga.areaId());
        }
        int added = 0;
        Set<Integer> anchoredVids = new HashSet<>(); // RUNDA 19b: gminy którym TEN call dodał głęboki wp (wiedza, nie re-check pozycji)
        Set<double[]> addedDeep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); // RUNDA 19c: świeże głębokie wp — NIE usuwać
        // RUNDA 22b: wstaw wp DOKŁADNIE w punkcie przecięcia śladu z buforem (`creditedCrossing.entry`) — INTERPOLOWANY
        // punkt na segmencie między wierzchołkami, NIE snap do wierzchołka. Odporny na długie proste odcinki (mało
        // wierzchołków) i na to że najbliższy wierzchołek wypada za granicą. entry leży 200m w głąb → W vid.
        int anchorFail = 0;
        for (int vid : covered) {
            if (restrictTo != null && !restrictTo.contains(vid)) continue; // PRZESUŃ: tylko gminy usuniętych spurów
            if (wpInside.contains(vid)) continue; // ma już KREDYTUJĄCY wp (w buforze) → nic do poprawy
            // RUNDA 53: re-kotwica MOVE-nie-ADD — znajdź istniejący nie-anchor wp tej gminy (płytki/zaułkowy; gdyby
            // kredytował, byłby w wpInside i już byśmy continue). Po wstawieniu głębokiego — USUNIEMY go (move, nie dup).
            double[] pOld = null;
            for (double[] p : route) {
                if (isAnchor(p, anchors)) continue;
                UnvisitedArea gp = gminaIndex.findGminaForPoint(p[0], p[1]);
                if (gp != null && gp.areaId() == vid) { pOld = p; break; }
            }
            velomarker.port.out.planning.AreaCoverageIndex.Crossing c = gminaIndex.creditedCrossing(track, vid);
            if (c == null) { if (anat) anatFails.add(vid + ":crossing=null"); continue; }
            double[] entry = c.entry(); // punkt przecięcia śladu z buforem (~200m w głąb) = serduszko
            // znajdź nogę + segment ZAWIERAJĄCY entry (najmniejszy dystans punkt→segment, nie wierzchołek)
            int bestLeg = -1, bestSeg = -1; double bestSD = Double.MAX_VALUE;
            for (int j = 0; j < route.size() - 1 && bestSD > 1e-7; j++) {
                List<double[]> g = getEdge(route.get(j), route.get(j + 1), cache, brouter, profile, alphaKmPerMeter).geometry();
                for (int m = 0; m < g.size() - 1; m++) {
                    double sd = pointToSegmentExactKm(entry, g.get(m), g.get(m + 1)); // RUNDA 23: analityczny (O(1), early-break działa)
                    if (sd < bestSD) { bestSD = sd; bestLeg = j; bestSeg = m; if (sd <= 1e-7) break; }
                }
            }
            UnvisitedArea ea = gminaIndex.findGminaForPoint(entry[0], entry[1]); // entry 200m w głąb → powinno być w vid
            if (bestLeg < 0 || bestSD > 0.05 || ea == null || ea.areaId() != vid) { anchorFail++;
                if (anat) anatFails.add(String.format(java.util.Locale.ROOT, "%d:%s(entry %.4f,%.4f)", vid,
                        bestLeg < 0 ? "no-segment" : ea == null ? "entry-no-gmina" : ea.areaId() != vid ? "entry-not-in-vid" : "entry-off-track(" + Math.round(bestSD * 1000) + "m)",
                        entry[0], entry[1]));
                continue; }
            EdgeCache.EdgeInfo be = getEdge(route.get(bestLeg), route.get(bestLeg + 1), cache, brouter, profile, alphaKmPerMeter);
            double[] anchorPt = entry.clone();
            seedSlicedEdgesAtPoint(cache, be, route.get(bestLeg), route.get(bestLeg + 1), bestSeg, anchorPt, alphaKmPerMeter);
            route.add(bestLeg + 1, anchorPt);
            selected.add(new SeedSel(ea, anchorPt, orderKey(anchorPt, baseline, baseCum), 0.0, minDistToBaselineKm(anchorPt, baseline)));
            if (stayOut != null) stayOut.add(anchorPt);
            added++;
            anchoredVids.add(vid);
            addedDeep.add(anchorPt);
            if (anat) anatAdds.add(String.format(java.util.Locale.ROOT, "#%d %d:%s@%.4f,%.4f", bestLeg + 1, vid,
                    ea.name(), anchorPt[0], anchorPt[1]));
            // RUNDA 53: MOVE — głęboki wp już wstawiony, więc usuń stary płytki/zaułkowy wp tej gminy (scal prev→next).
            // Net: gmina ma DOKŁADNIE 1 wp (przeniesiony na przelot), duplikat nie powstaje śródpętlowo.
            if (pOld != null && pOld != anchorPt) {
                int oi = identityIndexOf(route, pOld);
                if (oi > 0 && oi < route.size() - 1) {
                    double[][] merge = {route.get(oi - 1), route.get(oi + 1)};
                    final double[] po = pOld;
                    route.remove(oi); selected.removeIf(s -> s.point() == po);
                    cache.setReason("ogonek-scalenie");
                    prewarmPairs(java.util.Collections.singletonList(merge), cache, brouter, profile, alphaKmPerMeter);
                    cache.setReason("pomiar");
                    if (anat || debugGeoJson) log.info("ALNS2 RE-KOTWICA MOVE: vid={} pOld→głęboki (1 wp/gmina, bez duplikatu)", vid);
                }
            }
        }
        // RUNDA 19: po dodaniu głębokich wp — czyszczenie PŁYTKICH wp (w wielokącie, ale POZA buforem), TYLKO przy
        // entry-anchorze (restrictTo==null; inline-PRZESUŃ w passach pomija — spurami zajmuje się tailPrune).
        // BEZ restore-on-drop: decyzja per-wp Z GÓRY. Skoro pętla wyżej zakotwiczyła głęboko KAŻDĄ pokrytą gminę,
        // usunięcie płytkiego nie gubi pokrycia (gminę — w tym tranzytowe z nóg — trzyma głęboki wp). Wyjątek (anty-
        // dziura): gmina pokryta ALE niezakotwiczona (rzadka porażka anchora) → zostaw płytki.
        if (restrictTo == null) {
            // RUNDA 19b: „gmina ma głęboki wp" = WIEDZA (miała w buforze na starcie `wpInside` ∪ TEN call dodał
            // `anchoredVids`). NIE re-check pozycji: głęboki wp ląduje DOKŁADNIE na granicy bufora (200m) gdzie
            // JTS contains=false → re-check fałszywie mówił „brak głębokiego" i płytki zostawał.
            Set<Integer> deepGminaIds = new HashSet<>(wpInside);
            deepGminaIds.addAll(anchoredVids);
            List<double[]> toRemove = new ArrayList<>();
            int kept = 0;
            for (SeedSel s : new ArrayList<>(selected)) {
                double[] p = s.point();
                if (isAnchor(p, anchors)) continue;
                if (addedDeep.contains(p)) continue;                                           // RUNDA 19c: świeży głęboki wp (na granicy bufora) — NIE usuwać
                UnvisitedArea full = gminaIndex.findGminaForPoint(p[0], p[1]);
                if (full == null) continue;                                                   // poza wielokątami
                if (gminaIndex.findCreditedGminaForPoint(p[0], p[1]) != null) continue;        // w buforze = kredytuje → zostaw
                int gid = full.areaId();
                boolean cov = covered.contains(gid), wpIn = wpInside.contains(gid), anchd = anchoredVids.contains(gid);
                int pidx = anat ? identityIndexOf(route, p) : -1;
                if (cov && !deepGminaIds.contains(gid)) { kept++;                              // pokryta, niezakotwiczona → ZOSTAW
                    if (anat) anatShallow.add(String.format(java.util.Locale.ROOT, "#%d @%.4f,%.4f %s cov=%b wpIn=%b anchd=%b → ZOSTAW(pokryta,!głęboki)",
                            pidx, p[0], p[1], full.name(), cov, wpIn, anchd));
                    continue; }
                toRemove.add(p);                                                               // gmina ma głęboki wp albo niepokryta → USUŃ
                if (anat) anatShallow.add(String.format(java.util.Locale.ROOT, "#%d @%.4f,%.4f %s cov=%b wpIn=%b anchd=%b → USUŃ",
                        pidx, p[0], p[1], full.name(), cov, wpIn, anchd));
            }
            if (!toRemove.isEmpty()) {
                List<double[][]> mp = new ArrayList<>();
                for (double[] p : toRemove) {
                    int di = identityIndexOf(route, p);
                    if (di > 0 && di < route.size() - 1) mp.add(new double[][]{route.get(di - 1), route.get(di + 1)});
                }
                for (double[] p : toRemove) {
                    int di = identityIndexOf(route, p);
                    if (di >= 0) { route.remove(di); selected.removeIf(s -> s.point() == p); }
                }
                cache.setReason("ogonek-scalenie");
                prewarmPairs(mp, cache, brouter, profile, alphaKmPerMeter);                    // strzał — zmiana geometrii
                cache.setReason("pomiar");
                log.info("ALNS2 SHALLOW-CLEAN: usunięto={} płytkich wp (gmina ma głęboki wp / niepokryta), zostawiono={} (niezakotwiczone), reroute BROUTER",
                        new Object[]{toRemove.size(), kept});
            }
        }
        // RUNDA 21: szczegółowy log (jak SPUR-ANATOMIA) — co dodano, co nie udało się zakotwiczyć, co usunięto/zostawiono.
        if (anat && (!anatAdds.isEmpty() || !anatFails.isEmpty() || !anatShallow.isEmpty())) {
            log.info("ALNS2 ANCHOR-ANATOMIA [{}]: anchor+{} {} | fail{} {} | płytkie{}: {}",
                    new Object[]{debugPhase, anatAdds.size(), anatAdds, anatFails.size(), anatFails,
                            anatShallow.size(), anatShallow});
        }
        // RUNDA 14: BEZ log per-call (zasypywał logi cięcia). Caller loguje: kotwica wejściowa raz, suma PRZESUŃ
        // z passów wpada do TAIL-PRUNE v6 jako `przesuń+N`.
        return added;
    }

    /** Gminy przecinane przez REALNĄ geometrię krawędzi A→B (depth-aware, JTS). Czysty cache po edgeKey —
     *  geometria krawędzi jest deterministyczna, więc wpisy nigdy nie są inwalidowane. */
    private Set<Integer> edgeCrossings(double[] a, double[] b, Map<String, Set<Integer>> edgeCrossCache,
                                       EdgeCache cache, GminaIndex gminaIndex,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter) {
        String key = String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
        return edgeCrossCache.computeIfAbsent(key, k ->
                gminaIndex.visitedAreaIds(getEdge(a, b, cache, brouter, profile, alphaKmPerMeter).geometry()));
    }

    /**
     * Idzie po geometrii legu OD KOŃCA (entry-point, wewnątrz gminy) wstecz do pierwszego wierzchołka
     * POZA gminą (= przejście granicy), potem wraca {@code insideM} metrów W GŁĄB po tej samej geometrii
     * i zwraca interpolowany punkt. {@code null} gdy: koniec legu nie leży w gminie, cała geometria w gminie
     * (lollipop — prev w tej samej gminie), lub głębokość entry-pointu od granicy &lt; insideM (już płytko).
     */
    private static double[] walkInsideFromBoundary(List<double[]> legGeom, UnvisitedArea g, double insideM) {
        if (legGeom == null || legGeom.size() < 2) return null;
        int n = legGeom.size();
        if (!pointInArea(legGeom.get(n - 1), g)) return null;
        for (int i = n - 1; i > 0; i--) {
            double[] a = legGeom.get(i);      // bliżej entry-pointu (wewnątrz gminy)
            double[] b = legGeom.get(i - 1);  // bliżej prev
            if (pointInArea(b, g)) continue;
            // b POZA gminą → granica na segmencie b→a (przybliżenie: granica ≈ b, błąd ≤ długość segmentu,
            // typowo 10-50 m — akceptowalne przy insideM=300). Idź od b w stronę końca insideM metrów.
            double need = insideM;
            double segM = velomarker.service.planning.WaypointSelector.haversineKm(b, a) * 1000.0;
            if (need <= segM) {
                double t = need / segM;
                return new double[]{b[0] + (a[0] - b[0]) * t, b[1] + (a[1] - b[1]) * t};
            }
            need -= segM;
            for (int j = i; j < n - 1; j++) {
                double[] p = legGeom.get(j);
                double[] q = legGeom.get(j + 1);
                double sM = velomarker.service.planning.WaypointSelector.haversineKm(p, q) * 1000.0;
                if (need <= sM) {
                    double t = need / sM;
                    return new double[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t};
                }
                need -= sM;
            }
            return null; // dystans granica→entry-point < insideM → wp już płytko, nie ruszać
        }
        return null; // cała geometria legu w gminie (prev w tej samej gminie = lollipop)
    }

    /** Point-in-area na wszystkich częściach MultiPolygonu (outer + holes; punkt w hole = poza gminą). */
    private static boolean pointInArea(double[] p, UnvisitedArea g) {
        for (velomarker.entity.planning.AreaPart part : g.parts()) {
            if (velomarker.service.planning.WaypointSelector.pointInRing(p, part.outer())) {
                boolean inHole = false;
                if (part.holes() != null) {
                    for (double[][] hole : part.holes()) {
                        if (velomarker.service.planning.WaypointSelector.pointInRing(p, hole)) {
                            inHole = true;
                            break;
                        }
                    }
                }
                if (!inHole) return true;
            }
        }
        return false;
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
                // Explicit maxSamples = okno size → 1:1 lookup per coord. Bez tego default cap 500
                // dla okna 401 jest OK (cap > size), ale jawnie zapewnia że żaden cap globalny się
                // nie wkradnie i nie zaniży climb.
                List<double[]> window = coords.subList(i, end);
                total += elevation.sample(window, window.size()).gainM();
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

    /**
     * DEEP-BATCH LOOP (C, wizja usera): zastępuje densify+reconcile+fillspare. Po szybkim seedzie pętla
     * głębokich batchy: ZMIERZ → (jeśli <100%) DOBIERZ (cheapest-insertion, BEZ re-sortu) → CLEAN (prune
     * bezużytecznych + 2-opt + re-snap) → ZMIERZ. Stop gdy effort ∈ [100%,103%] (finito) lub brak kandydatów / MAX_DEEP.
     * Przy przestrzale >103% rollback doboru + maxAdds/2 (dobierz proporcjonalnie mniej).
     */
    private RouteCalculation deepBatchRefine(List<double[]> route, RoutePreferences prefs, List<UnvisitedArea> pool,
                                             GminaIndex gminaIndex, RouteCalculation calc,
                                             BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
                                             double alpha, double totalLimit, Map<String, Double> rewards,
                                             List<double[]> anchors, Consumer<UUID> checkCancel, UUID taskId) {
        final double lo = totalLimit * 1.00, hi = totalLimit * 1.03;
        // ADD wolno przestrzelić do ~107%: po ADD budżet jest NAJWYŻSZY, CLEAN (prune+graź-resnap) i tak go obniża.
        // Pasmo końcowe [lo,hi] sprawdzane PO CLEAN (koniec batcha) — nie dusimy ADD na 103%.
        final double addCeil = totalLimit * 1.07;
        final int MAX_DEEP = 8;
        final double ADD_R_KM = 20.0;
        Map<Integer, Double> areaReward = new HashMap<>();
        for (UnvisitedArea a : pool) areaReward.put(a.areaId(), rewards.getOrDefault(rewardCategoryKey(a), 1.0));
        int maxAdds = Integer.MAX_VALUE;
        // MARKER WERSJI (B/C/E/relocate+heal): jeśli w logu widzisz „addCeil=107%" + „relocate+heal", nowy kod jest LIVE.
        log.info("DEEP-BATCH START [v2: graź-resnap + addCeil=107% + gv-prune + relocate + heal-guard], pasmo=[{}, {}], addCeil={}",
                new Object[]{Math.round(lo), Math.round(hi), Math.round(addCeil)});
        for (int it = 0; it < MAX_DEEP; it++) {
            checkCancel.accept(taskId);
            double eff = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
            int visited0 = gminaIndex.visitedAreaIds(calc.coordinates()).size();
            log.info("=== DEEP-BATCH iter {}: effort={}/{} ({}%), wp={}, gminy={} ===",
                    new Object[]{it, Math.round(eff), Math.round(totalLimit), Math.round(eff * 100.0 / totalLimit),
                            route.size(), visited0});
            boolean inBand = eff >= lo && eff <= hi;
            // DOBIERZ tylko gdy poniżej 100% (gdy >103% → CLEAN obniży; gdy w paśmie → tylko CLEAN i koniec).
            if (eff < lo) {
                List<double[]> snapshot = new ArrayList<>(route);
                Set<Integer> visited = gminaIndex.visitedAreaIds(calc.coordinates());
                List<double[]> geom = subsampleGeometry(calc.coordinates(), 4000);
                double room = addCeil - eff; // pozwól ADD wypełnić do ~107% (CLEAN ściągnie w pasmo)
                // ENCLOSED-FIRST: dziury OTOCZONE (donut-holes w środku pokrycia) przed peryferyjnymi (na zewnątrz)
                // — inaczej pętla rozlewałaby się na zewnątrz zamiast łatać dziury. Peryferyjne tylko dopełniają budżet.
                record Cand(double[] ep, double detEffort, double ratio, boolean enclosed) {}
                List<Cand> cands = new ArrayList<>();
                for (UnvisitedArea a : pool) {
                    if (visited.contains(a.areaId())) continue;
                    double d = gminaIndex.distToRoute(a, geom);
                    if (d > ADD_R_KM) continue;
                    double[][] samples = gminaIndex.samplePointsFor(a);
                    double[] ep = sampleNearestToGeometry(samples, null, geom);
                    if (ep == null) ep = samples[0];
                    double bestDet = Double.MAX_VALUE;
                    for (int i = 0; i < route.size() - 1; i++) {
                        double det = hav(route.get(i), ep) + hav(ep, route.get(i + 1)) - hav(route.get(i), route.get(i + 1));
                        if (det < bestDet) bestDet = det;
                    }
                    double detEffort = Math.max(0.05, bestDet * 1.3);
                    boolean enclosed = gminaIndex.isEnclosedHole(a, visited, HOLE_ENCLOSED_FRACTION);
                    cands.add(new Cand(ep, detEffort, areaReward.getOrDefault(a.areaId(), 1.0) / detEffort, enclosed));
                }
                if (cands.isEmpty()) { log.info("DEEP-BATCH: brak kandydatów ≤{}km — stop", ADD_R_KM); break; }
                // sort: enclosed (otoczone) NAJPIERW, potem wg reward/cost
                cands.sort((x, y) -> x.enclosed() != y.enclosed()
                        ? Boolean.compare(y.enclosed(), x.enclosed())
                        : Double.compare(y.ratio(), x.ratio()));
                int added = 0, addedEnclosed = 0;
                double used = 0;
                for (Cand c : cands) {
                    if (added >= maxAdds) break;
                    if (used + c.detEffort() > room) continue;
                    cheapestInsert(route, c.ep());
                    used += c.detEffort();
                    added++;
                    if (c.enclosed()) addedEnclosed++;
                }
                if (added == 0) { cheapestInsert(route, cands.get(0).ep()); added = 1; } // co najmniej 1 najlepszy
                RouteCalculation next;
                try {
                    next = brouter.apply(buildWaypoints(route, prefs), profile);
                } catch (RuntimeException ex) {
                    route.clear(); route.addAll(snapshot);
                    maxAdds = Math.max(1, added / 2);
                    log.warn("DEEP-BATCH iter {}: reroute fail ({}) → rollback, maxAdds={}", new Object[]{it, ex.getMessage(), maxAdds});
                    continue;
                }
                double nextEff = next.distanceKm() + alpha * accurateClimbM(next.coordinates());
                if (nextEff > addCeil) { // rollback DOPIERO powyżej 107% (CLEAN ściągnie 103-107% w pasmo)
                    route.clear(); route.addAll(snapshot);
                    maxAdds = Math.max(1, added / 2);
                    log.info("DEEP-BATCH iter {}: ADD przestrzelił ({}% > 107%) → rollback, maxAdds={}",
                            new Object[]{it, Math.round(nextEff * 100.0 / totalLimit), maxAdds});
                    continue;
                }
                log.info("DEEP-BATCH iter {}: dodano {} obszarów ({} enclosed-dziur + {} peryferyjnych) (effort {}%)",
                        new Object[]{it, added, addedEnclosed, added - addedEnclosed, Math.round(nextEff * 100.0 / totalLimit)});
                calc = next;
                debugCalc("deep" + it + "-add", calc, route);
            }
            // CLEAN zawsze (sprząta po doborze / obniża gdy >103% / finalne tidy gdy w paśmie)
            calc = cleanRoute(route, prefs, pool, gminaIndex, brouter, profile, alpha, calc, anchors, "deep" + it);
            double eff2 = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
            log.info("DEEP-BATCH iter {} po CLEAN: effort={}/{} ({}%), wp={}, gminy={}",
                    new Object[]{it, Math.round(eff2), Math.round(totalLimit), Math.round(eff2 * 100.0 / totalLimit),
                            route.size(), gminaIndex.visitedAreaIds(calc.coordinates()).size()});
            debugCalc("deep" + it + "-end", calc, route); // ADMIN DEBUG: stan na koniec deep-batcha
            if (inBand || (eff2 >= lo && eff2 <= hi)) { log.info("DEEP-BATCH: effort w paśmie [100,103] → finito"); break; }
        }
        return calc;
    }

    // ── DEEP-BATCH LOOP (C) — wspólne helpery ──────────────────────────────────────────────────────────
    private static final double CLEAN_SPUR_KM = 4.0;        // detour by uznać wp za spur (jak seed SPUR_DETOUR_KM)
    private static final double CLEAN_RESTORE_R_KM = 15.0;  // promień restore/revert dropped (jak re-snap guard)

    private static double hav(double[] a, double[] b) {
        return velomarker.service.planning.WaypointSelector.haversineKm(a, b);
    }

    /** ADMIN DEBUG: snapshot realnej geometrii + waypointów po reroute (faza deep-batcha). Guarded debugGeoJson. */
    private void debugCalc(String phase, RouteCalculation c, List<double[]> route) {
        if (debugGeoJson) debugGeometry(phase, c.coordinates(), route, c.distanceKm());
    }

    /** Wstaw entry-point w pozycję o najmniejszym detourze (cheapest-insertion, bez re-sortu). */
    private static void cheapestInsert(List<double[]> route, double[] ep) {
        int bestI = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.size() - 1; i++) {
            double det = hav(route.get(i), ep) + hav(ep, route.get(i + 1)) - hav(route.get(i), route.get(i + 1));
            if (det < best) { best = det; bestI = i; }
        }
        route.add(bestI + 1, ep);
    }

    /**
     * DEEP-LOOP krok CLEAN: route-based transit-aware prune (usuń bezużyteczne wp — lollipop/spur/bez-gminy, ale
     * COFNIJ usunięcie gdy zdjęłoby gminę) + 2-opt + re-snap entry-pointów do cięciwy (z transit-guardem). Mutuje
     * {@code route}, zwraca nowy {@link RouteCalculation}. Operuje na RAW route (cheapest-insertion, BEZ re-sortu).
     */
    private RouteCalculation cleanRoute(List<double[]> route, RoutePreferences prefs, List<UnvisitedArea> pool,
                                        GminaIndex gminaIndex, BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                        String profile, double alpha, RouteCalculation calc, List<double[]> anchors,
                                        String phase) {
        Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);

        // ── 1) PRUNE bezużytecznych (transit-aware) ──
        Set<Integer> visitedBefore = gminaIndex.visitedAreaIds(calc.coordinates());
        // gv = ile razy każda gmina jest zaliczana w REALNEJ geometrii. gv≥2 → gmina pokryta gdzie indziej
        // (tranzyt/inny wp) → wp w niej jest redundantnym spurem (np. wp102/Łomianki: zaliczone przez przejazd
        // przez miasto). Łapie spur niezależny od haversine-detouru (realny „skręt w lewo" niewidoczny dla prostych).
        Map<Integer, Integer> gv = countVisitsPerArea(calc.coordinates(), gminaIndex);
        Set<double[]> candRemove = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<double[]> candList = new ArrayList<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] cur = route.get(i);
            if (isAnchor(cur, anchors)) continue;
            UnvisitedArea g = gminaIndex.findGminaForPoint(cur[0], cur[1]);
            double[] prev = route.get(i - 1), next = route.get(i + 1);
            UnvisitedArea gp = gminaIndex.findGminaForPoint(prev[0], prev[1]);
            UnvisitedArea gn = gminaIndex.findGminaForPoint(next[0], next[1]);
            boolean lollipop = g != null && ((gp != null && gp.areaId() == g.areaId()) || (gn != null && gn.areaId() == g.areaId()));
            double detour = hav(prev, cur) + hav(cur, next) - hav(prev, next);
            boolean spur = detour > CLEAN_SPUR_KM;
            boolean noOwner = g == null; // wp nic nie zalicza (poza pulą) → bezużyteczny
            boolean gvRedundant = g != null && gv.getOrDefault(g.areaId(), 0) >= 2; // gmina pokryta ≥2× → spur redundantny
            if (lollipop || spur || noOwner || gvRedundant) { candRemove.add(cur); candList.add(cur); }
        }
        if (!candRemove.isEmpty()) {
            route.removeIf(candRemove::contains);
            calc = brouter.apply(buildWaypoints(route, prefs), profile);
            debugCalc(phase + "-prune", calc, route);
            Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(calc.coordinates());
            Set<Integer> dropped = new HashSet<>(visitedBefore);
            dropped.removeAll(visitedAfter);
            if (!dropped.isEmpty()) {
                List<double[]> restore = new ArrayList<>();
                for (double[] p : candList) {
                    UnvisitedArea g = gminaIndex.findGminaForPoint(p[0], p[1]);
                    boolean keep = g != null && dropped.contains(g.areaId());
                    if (!keep) for (int did : dropped) {
                        UnvisitedArea da = idToArea.get(did);
                        if (da != null && hav(p, new double[]{da.lng(), da.lat()}) <= CLEAN_RESTORE_R_KM) { keep = true; break; }
                    }
                    if (keep) restore.add(p);
                }
                if (!restore.isEmpty()) {
                    for (double[] ep : restore) cheapestInsert(route, ep);
                    calc = brouter.apply(buildWaypoints(route, prefs), profile);
                    debugCalc(phase + "-prune-restore", calc, route);
                }
            }
        }

        // ── 2) 2-opt + RELOCATE (Or-opt) ──
        // 2-opt rozplątuje skrzyżowania, ale NIE przenosi pojedynczego wp — szpic „w bok i z powrotem" do
        // potrzebnej gminy (Borowina-typ, gv==1) zostaje. relocate przenosi taki wp na krawędź trasy która i tak
        // przechodzi blisko → płynny przejazd zamiast szpica. Zachowuje WSZYSTKIE wp (zero utraty pokrycia z relokacji).
        Alns2LocalSearch.twoOpt(route);
        Alns2LocalSearch.relocate(route);
        calc = brouter.apply(buildWaypoints(route, prefs), profile);
        debugCalc(phase + "-2opt", calc, route);

        // ── 3) RE-SNAP do cięciwy (route-based, transit-guard) ──
        Set<Integer> vBeforeSnap = gminaIndex.visitedAreaIds(calc.coordinates());
        List<Integer> snapIdx = new ArrayList<>();
        List<double[]> snapOrig = new ArrayList<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] cur = route.get(i);
            if (isAnchor(cur, anchors)) continue;
            UnvisitedArea g = gminaIndex.findGminaForPoint(cur[0], cur[1]);
            if (g == null) continue;
            double[][] samples = gminaIndex.grazePointsFor(g); // GRAŹ: płytkie (~280m), przy granicy nie 500m w głąb
            double[] prev = route.get(i - 1), next = route.get(i + 1);
            double[] best = cur;
            double bestD = pointToSegmentKm(cur, prev, next);
            for (double[] s : samples) {
                double d = pointToSegmentKm(s, prev, next);
                if (d < bestD) { bestD = d; best = s; }
            }
            if (best != cur) { snapIdx.add(i); snapOrig.add(cur); route.set(i, best); }
        }
        if (!snapIdx.isEmpty()) {
            calc = brouter.apply(buildWaypoints(route, prefs), profile);
            debugCalc(phase + "-resnap", calc, route);
            Set<Integer> vAfterSnap = gminaIndex.visitedAreaIds(calc.coordinates());
            Set<Integer> droppedSnap = new HashSet<>(vBeforeSnap);
            droppedSnap.removeAll(vAfterSnap);
            if (!droppedSnap.isEmpty()) {
                boolean anyRevert = false;
                for (int k = 0; k < snapIdx.size(); k++) {
                    int i = snapIdx.get(k);
                    double[] snapped = route.get(i);
                    UnvisitedArea g = gminaIndex.findGminaForPoint(snapped[0], snapped[1]);
                    boolean revert = g != null && droppedSnap.contains(g.areaId());
                    if (!revert) for (int did : droppedSnap) {
                        UnvisitedArea da = idToArea.get(did);
                        if (da != null && hav(snapped, new double[]{da.lng(), da.lat()}) <= CLEAN_RESTORE_R_KM) { revert = true; break; }
                    }
                    if (revert) { route.set(i, snapOrig.get(k)); anyRevert = true; }
                }
                if (anyRevert) { calc = brouter.apply(buildWaypoints(route, prefs), profile); debugCalc(phase + "-resnap-revert", calc, route); }
            }
        }

        // ── 4) GUARD POKRYCIA (catch-all) ──
        // 2-opt/relocate przestawiają kolejność → zmieniają ścieżkę tranzytową → mogą zgubić gminę zaliczaną
        // TRANZYTEM (nie ma na to per-ruch guardu jak przy prune/resnap). Przy budżecie ~100% deep-loop już nie
        // dobierze (ADD tylko gdy <100%) → trwała DZIURA. Tu domykamy: każdą gminę zgubioną względem wejścia do
        // cleanRoute dorabiamy z powrotem (graź-punkt, cheapest-insert) + jeden reroute. cleanRoute = niemalejący po pokryciu.
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(calc.coordinates());
        Set<Integer> dropped = new HashSet<>(visitedBefore);
        dropped.removeAll(visitedAfter);
        if (!dropped.isEmpty()) {
            int healed = 0;
            for (int did : dropped) {
                UnvisitedArea da = idToArea.get(did);
                if (da == null) continue;
                double[][] graze = gminaIndex.grazePointsFor(da);
                cheapestInsert(route, graze.length > 0 ? graze[0] : new double[]{da.lng(), da.lat()});
                healed++;
            }
            if (healed > 0) {
                calc = brouter.apply(buildWaypoints(route, prefs), profile);
                debugCalc(phase + "-heal", calc, route);
                log.info("CLEAN [{}]: guard pokrycia dorobił {} zgubionych gmin", new Object[]{phase, healed});
            }
        }
        return calc;
    }

    private RouteCalculation reconcileNearRouteHoles(
            List<double[]> route, RoutePreferences prefs, List<UnvisitedArea> pool,
            GminaIndex gminaIndex, RouteCalculation calc,
            BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
            double alpha, double totalLimit, Map<String, Double> rewards,
            Consumer<UUID> checkCancel, UUID taskId) {
        final double ceiling = totalLimit * 1.03;   // sufit 103% (było 1.10) — mniej straszący w UI
        final double MULT = 1.3;        // detour km → effort proxy (road factor)
        final int MAX_RECONCILE = 5;    // user: więcej passes pozwala dolatać dziury które zostały po iter 0-1
        final int MAX_FIT = 2;          // ile razy skurczyć batch i przeroutować, gdy proxy przestrzeli 103%
        // Rollback-CONTINUE: po overshoot/reward-drop nie break całej pętli, tylko zredukuj batch
        // dla kolejnego iter (konserwatywniej). Tracona była potencjalna 3-4 iter szansa.
        int maxHolesThisIter = Integer.MAX_VALUE;
        // Final summary tracking — by w logu było widać która iteracja jest FINALNĄ trasą (calc).
        int acceptedIters = 0;
        int rejectedIters = 0;
        int lastAcceptedIter = -1;
        Map<Integer, Integer> attempts = new HashMap<>(); // areaId → ile prób wstawienia (eskalacja entry)
        // Reward per obszar (areaId → wartość kategorii). Swap CHRONI wysokowartościowe (rzadkie DE Kreise),
        // tnie tanie peryferyjne (gęste CZ gminy). Bez tego swap ciął najdłuższą mackę = często cenny powiat.
        Map<Integer, Double> areaReward = new HashMap<>();
        for (UnvisitedArea a : pool) {
            areaReward.put(a.areaId(), rewards.getOrDefault(rewardCategoryKey(a), 1.0));
        }
        for (int rIter = 0; rIter < MAX_RECONCILE; rIter++) {
            checkCancel.accept(taskId);
            // Adaptive R: iter 0-1: 15 km (standard), iter 2: 20, iter 3: 25, iter 4: 30.
            // Gdy seed-pasmo niskie (80-85%) i wcześniejsze iter nie znalazły dużo dziur — rozszerzamy
            // promień, by złapać peryferyjne enclosed.
            final double R = rIter < 2 ? 15.0 : (rIter < 3 ? 20.0 : (rIter < 4 ? 25.0 : 30.0));
            List<double[]> geom = subsampleGeometry(calc.coordinates(), 4000);
            Set<Integer> visited = gminaIndex.visitedAreaIds(calc.coordinates()); // STRICT (≥ głębokość)
            double currentEffort = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
            if (currentEffort >= ceiling) break; // już ponad 103% — nic nie dokładamy
            final double room = ceiling - currentEffort; // zapas do 103%
            // Dziury WEWNĘTRZNE (otoczone świeżo zaliczonymi), najbliższe pierwsze. distToRoute ≤R to tylko
            // TANI pre-filtr wydajności (ile gmin liczyć enclosed); twardy warunek = isEnclosedHole — odsiewa
            // peryferyjną obwódkę z boku trasy (była głównym źródłem fałszywych dziur i przestrzelenia budżetu).
            // Entry-point ESKALUJE per próba ({@link #interiorEntryPoints}).
            record Hole(UnvisitedArea a, double dist) {}
            List<Hole> holesAll = new ArrayList<>();
            for (UnvisitedArea a : pool) {
                if (visited.contains(a.areaId())) continue;
                double d = gminaIndex.distToRoute(a, geom);
                if (d > R) continue;
                if (!gminaIndex.isEnclosedHole(a, visited, HOLE_ENCLOSED_FRACTION)) continue;
                holesAll.add(new Hole(a, d));
            }
            if (holesAll.isEmpty()) break;
            // Limit per iter — po rollback poprzedniego iter `maxHolesThisIter` jest połowicznie zredukowany,
            // więc konserwatywniej, ale wciąż grupa. Sort dist ASC → bliższe pierwsze.
            holesAll.sort(Comparator.comparingDouble(Hole::dist));
            List<Hole> holes = holesAll.size() > maxHolesThisIter
                    ? new ArrayList<>(holesAll.subList(0, maxHolesThisIter))
                    : holesAll;
            // (holes już posortowane wcześniej przez holesAll.sort)
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
            // Per-hole tracking (areaId + name) — do logowania ZALICZONE vs NIEzaliczone po reroute.
            // Bez tego nie wiadomo czemu „wstawiono 1 dziur" + visited się nie zmieniło (entry-point nieroutowalny).
            record TriedHole(int areaId, String name) {}
            List<TriedHole> tried = new ArrayList<>();
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
                    tried.add(new TriedHole(h.a().areaId(), h.a().name()));
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
                    tried.add(new TriedHole(h.a().areaId(), h.a().name()));
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
                // Reroute fail = sygnał wyspowy. Spróbuj dalej z 1/2 batchem (może mniejszy zbiór
                // uniknie wyspy). MAX_RECONCILE i tak zatrzyma.
                maxHolesThisIter = Math.max(1, inserted / 2);
                rejectedIters++;
                log.info("ALNS2 reconcile iter {}: ODRZUCAM (BRouter reroute fail) — zostaję na poprzedniej trasie, kolejny iter z 1/2 batch", rIter);
                continue;
            }
            // HARD CAP + GWARANCJA WARTOŚCI: zweryfikuj REALNY effort (dokładny climb oknami) ORAZ że łączny
            // REWARD pokrycia nie spadł istotnie. Liczymy reward (nie sam licznik) — swap świadomie tnie kilka
            // tanich peryferyjnych gmin na rzecz dziur wewnętrznych (licznik może spaść o parę), ale NIE wolno
            // mu skasować wartościowych obszarów. Próg ≤3% straty rewardu = anti-katastrofa: dawny licznikowy
            // guard przepuszczał „1 duży Kreis → 2 tanie dziury" (licznik rósł, wartość leciała w dół).
            Set<Integer> afterIds = gminaIndex.visitedAreaIds(next.coordinates());
            double rewardBefore = sumReward(visited, areaReward);
            double rewardAfter = sumReward(afterIds, areaReward);
            boolean overCap = nextEffort > ceiling;
            boolean rewardDrop = rewardAfter < rewardBefore * 0.97;
            if (overCap || rewardDrop) {
                route.clear();
                route.addAll(snapshot);
                double currEffort = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
                long currPct = Math.round(currEffort * 100.0 / totalLimit);
                String reason;
                if (overCap && rewardDrop) {
                    reason = String.format(java.util.Locale.ROOT,
                            "PRZEKROCZONY CAP 103%% (próba effort=%d > %d) + spadek rewardu %.1f → %.1f (próg -3%%)",
                            Math.round(nextEffort), Math.round(ceiling), rewardBefore, rewardAfter);
                } else if (overCap) {
                    reason = String.format(java.util.Locale.ROOT,
                            "PRZEKROCZONY CAP 103%% (próba effort=%d > %d, fitTries %d nie skurczyły dość)",
                            Math.round(nextEffort), Math.round(ceiling), fitTries);
                } else {
                    reason = String.format(java.util.Locale.ROOT,
                            "SPADEK REWARDU %.1f → %.1f (-%.1f%%, próg -3%%) — swap wymienił cenne obszary na tańsze",
                            rewardBefore, rewardAfter, (rewardBefore - rewardAfter) * 100.0 / Math.max(0.01, rewardBefore));
                }
                log.info("=== ALNS2 reconcile iter {} ODRZUCONE: {} | rollback → zostaje POPRZEDNIA trasa (effort={}/{}={}%, visited={}) | kolejny iter z 1/2 batch ===",
                        new Object[]{rIter, reason, Math.round(currEffort), Math.round(totalLimit),
                                currPct, visited.size()});
                // ROLLBACK-CONTINUE: nie kończ pętli — kolejny iter spróbuje z 1/2 batchem dziur. User: „kto
                // broni zrobic kolejną by sprobowac uderzyc konserwatywniej". Wtedy uderzymy bliżej 103%.
                maxHolesThisIter = Math.max(1, inserted / 2);
                rejectedIters++;
                continue;
            }
            // Sukces iter — reset limitu na pełen batch dla następnego.
            maxHolesThisIter = Integer.MAX_VALUE;
            acceptedIters++;
            lastAcceptedIter = rIter;
            // Diagnostyka per-dziura: które z TriedHole rzeczywiście wpadły do afterIds (zaliczone)
            // a które nie (entry-point nieroutowalny / nadal poza progiem głębokości). Bez tego nie
            // wiadomo czemu „wstawiono N" + „visited się nie zmieniło" — przyczyna ukryta.
            List<String> gained = new ArrayList<>();
            List<String> notCredited = new ArrayList<>();
            for (TriedHole th : tried) {
                if (afterIds.contains(th.areaId()) && !visited.contains(th.areaId())) gained.add(th.name());
                else if (!afterIds.contains(th.areaId())) notCredited.add(th.name());
                // jeśli była już w `visited` przed (rare race) — pomijamy z logu
            }
            String gainedDump = gained.size() <= 25 ? String.join(", ", gained)
                    : String.join(", ", gained.subList(0, 25)) + " +" + (gained.size() - 25);
            String failedDump = notCredited.size() <= 15 ? String.join(", ", notCredited)
                    : String.join(", ", notCredited.subList(0, 15)) + " +" + (notCredited.size() - 15);
            long effortPct = Math.round(nextEffort * 100.0 / totalLimit);
            long overshoot = Math.round(nextEffort - totalLimit);
            log.info("=== ALNS2 reconcile iter {} BUDŻET-RAPORT: effort={} / budget={} = {}% (przekroczenie {}{} = km+0.1×wznios) ===",
                    new Object[]{rIter, Math.round(nextEffort), Math.round(totalLimit), effortPct,
                            overshoot >= 0 ? "+" : "", overshoot});
            log.info("ALNS2 reconcile iter {}: wstawiono {} dziur, zaliczone {} [{}], NIEzaliczone {} [{}], ucięto {} macek, fitTries {}, realKm {} → {}, effort {}/{} ({}%), visited → {}",
                    new Object[]{rIter, inserted, gained.size(), gainedDump, notCredited.size(), failedDump,
                            swapped, fitTries, Math.round(calc.distanceKm()),
                            Math.round(next.distanceKm()), Math.round(nextEffort), Math.round(totalLimit), effortPct, afterIds.size()});
            debugGeometry("reconcile-iter" + rIter, next.coordinates(), route, next.distanceKm()); // ADMIN DEBUG: realna geometria + waypointy (z wstawionymi dziurami) po tej iteracji reconcile
            calc = next;
        }
        // Final summary: która iteracja jest FINALNĄ trasą zwracaną z reconcile? `calc` to ostatnia
        // ZAAKCEPTOWANA iteracja (rollback przywraca snapshot przed kolejnym continue). User w logu nie
        // widział „która zwyciężyła", więc tu jawnie: liczba accepted/rejected + numer ostatniej accepted.
        double finalEffortLog = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
        long finalPct = Math.round(finalEffortLog * 100.0 / totalLimit);
        Set<Integer> finalVisited = gminaIndex.visitedAreaIds(calc.coordinates());
        String acceptedLabel = lastAcceptedIter >= 0 ? ("iter " + lastAcceptedIter) : "ŻADNA (calc = seed sprzed reconcile)";
        log.info("=== ALNS2 RECONCILE FINAL: zaakceptowane {}/odrzucone {} (z {} max), zwycięska = {} | effort={}/{}={}%, visited={} ===",
                new Object[]{acceptedIters, rejectedIters, MAX_RECONCILE, acceptedLabel,
                        Math.round(finalEffortLog), Math.round(totalLimit), finalPct, finalVisited.size()});
        return calc;
    }

    /**
     * FILL-SPARE: post-reconcile dorzucanie kandydatów BEZ filtru enclosed, sort reward/cost DESC.
     * Triggerowane gdy reconcile zostawił effort < 95% targetu. Cel: użyć spare budget na peryferyjne
     * objazdy ("objazd tu i ówdzie" - user). Twardy cap 103%, rollback-continue z 1/2 batch jak reconcile.
     */
    private RouteCalculation fillSpareBudget(
            List<double[]> route, RoutePreferences prefs, List<UnvisitedArea> pool,
            GminaIndex gminaIndex, RouteCalculation calc,
            BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
            double alpha, double totalLimit, Map<String, Double> rewards,
            Consumer<UUID> checkCancel, UUID taskId) {
        final double ceiling = totalLimit * 1.03;
        final double MULT = 1.3;
        // 5 iter (jak reconcile) + adaptive R: iter 0-1 standard 40 km, później rozszerzamy.
        // Po fix-em 2 → 5 iter używamy resztę budżetu na peryferyjne kandydaty (Bretania,
        // Pireneje, Massif Central, granica wschodniej PL) których reconcile nie złapał, bo
        // nie były enclosed (8-NN to też dziury). Rollback-continue (1/2 batch) już istnieje
        // i chroni przed overshoot.
        final int MAX_FILL_ITER = 5;
        Map<Integer, Double> areaReward = new HashMap<>();
        for (UnvisitedArea a : pool) {
            areaReward.put(a.areaId(), rewards.getOrDefault(rewardCategoryKey(a), 1.0));
        }
        int maxAddsThisIter = Integer.MAX_VALUE;
        for (int it = 0; it < MAX_FILL_ITER; it++) {
            checkCancel.accept(taskId);
            // Adaptive R: 40/40/60/90/150 km. Stała 40 zostawiała 8% budżetu nieużyte przy peryferyjnych
            // dziurkach kontynentalnych. NIE odpalamy „desperate" bez R — wyspy (Korsyka) zostawione
            // świadomie, fill-spare nie próbuje rozpiąć BRouter calls na ferry routing 200+ km.
            final double R_FILL_KM = it < 2 ? 40.0
                                  : it < 3 ? 60.0
                                  : it < 4 ? 90.0
                                  : 150.0;
            List<double[]> geom = subsampleGeometry(calc.coordinates(), 4000);
            Set<Integer> visited = gminaIndex.visitedAreaIds(calc.coordinates());
            double currentEffort = calc.distanceKm() + alpha * accurateClimbM(calc.coordinates());
            if (currentEffort >= ceiling) break;
            final double room = ceiling - currentEffort;
            // Kandydaci: unvisited w R_FILL_KM od trasy, BEZ enclosed-filter.
            record Cand(UnvisitedArea a, double detEffort, double reward, double ratio, int insertAt, double[] ep) {}
            List<Cand> cands = new ArrayList<>();
            for (UnvisitedArea a : pool) {
                if (visited.contains(a.areaId())) continue;
                double d = gminaIndex.distToRoute(a, geom);
                if (d > R_FILL_KM) continue;
                double[][] samples = gminaIndex.samplePointsFor(a);
                double[] ep = sampleNearestToGeometry(samples, null, geom);
                if (ep == null) ep = samples.length > 0 ? samples[0] : new double[]{a.lng(), a.lat()};
                double f = 0.5;
                double[] entry = new double[]{ep[0] + (a.lng() - ep[0]) * f, ep[1] + (a.lat() - ep[1]) * f};
                int bestI = -1;
                double bestDetour = Double.MAX_VALUE;
                for (int i = 0; i < route.size() - 1; i++) {
                    double det = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), entry)
                            + velomarker.service.planning.WaypointSelector.haversineKm(entry, route.get(i + 1))
                            - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
                    if (det < bestDetour) { bestDetour = det; bestI = i; }
                }
                double detEffort = Math.max(0.05, bestDetour * MULT);
                double reward = areaReward.getOrDefault(a.areaId(), 1.0);
                double ratio = reward / Math.max(0.05, detEffort);
                cands.add(new Cand(a, detEffort, reward, ratio, bestI, entry));
            }
            if (cands.isEmpty()) break;
            cands.sort((a, b) -> Double.compare(b.ratio(), a.ratio())); // DESC reward/cost
            List<double[]> snapshot = new ArrayList<>(route);
            List<Cand> tried = new ArrayList<>();
            double usedEffort = 0;
            for (Cand c : cands) {
                if (tried.size() >= maxAddsThisIter) break;
                if (usedEffort + c.detEffort() > room) continue;
                route.add(c.insertAt() + 1 + tried.size(), c.ep());
                tried.add(c);
                usedEffort += c.detEffort();
            }
            if (tried.isEmpty()) break;
            // Dedup adjacent (BRouter rzuca Index -1 na zero-długich segmentach).
            for (int i = route.size() - 2; i >= 1; i--) {
                if (velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1)) < 0.002) {
                    route.remove(i);
                }
            }
            RouteCalculation next;
            try {
                next = brouter.apply(buildWaypoints(route, prefs), profile);
            } catch (RuntimeException ex) {
                route.clear();
                route.addAll(snapshot);
                log.warn("FILL-SPARE iter {} (R={}km) re-route failed ({}) — próbuję dalej z 1/2 batch",
                        new Object[]{it, Math.round(R_FILL_KM), ex.getMessage()});
                maxAddsThisIter = Math.max(1, tried.size() / 2);
                continue;
            }
            double nextEffort = next.distanceKm() + alpha * accurateClimbM(next.coordinates());
            Set<Integer> afterIds = gminaIndex.visitedAreaIds(next.coordinates());
            double rewardBefore = sumReward(visited, areaReward);
            double rewardAfter = sumReward(afterIds, areaReward);
            if (nextEffort > ceiling || rewardAfter < rewardBefore * 0.97) {
                route.clear();
                route.addAll(snapshot);
                log.info("=== FILL-SPARE iter {} (R={}km) ROLLBACK: effort={}/{} ({}%) — kolejny iter z 1/2 batch ===",
                        new Object[]{it, Math.round(R_FILL_KM), Math.round(nextEffort), Math.round(totalLimit),
                                Math.round(nextEffort * 100.0 / totalLimit)});
                maxAddsThisIter = Math.max(1, tried.size() / 2);
                continue;
            }
            int gained = 0;
            List<String> gainedNames = new ArrayList<>();
            for (Cand c : tried) {
                if (afterIds.contains(c.a().areaId()) && !visited.contains(c.a().areaId())) {
                    gained++;
                    if (gainedNames.size() < 25) gainedNames.add(c.a().name());
                }
            }
            log.info("=== FILL-SPARE iter {} (R={}km): wstawiono {} kand. (reward/cost), zaliczone {} [{}], effort {}/{} ({}%), visited → {} ===",
                    new Object[]{it, Math.round(R_FILL_KM), tried.size(), gained, String.join(", ", gainedNames),
                            Math.round(nextEffort), Math.round(totalLimit),
                            Math.round(nextEffort * 100.0 / totalLimit), afterIds.size()});
            calc = next;
            maxAddsThisIter = Integer.MAX_VALUE; // reset po sukcesie
        }
        return calc;
    }

    /**
     * Punkty wjazdu dla reconcile, ESKALUJĄCE wg liczby prób ({@code att}): att 0 → ½ drogi od
     * edge-sample do centroidu (płytko, tanio); att 1 → centroid (głęboko). att ≥ 2 → pusta lista
     * = pomiń dziurę (route-nearest i centroid wyczerpane, kolejne iteracje by liczyły TEN SAM
     * centroid → te same BRouter calls bez zysku, jak w PL teście gdzie iter 2/3/4 inserted=1
     * a visited się nie ruszało).
     */
    private List<double[]> interiorEntryPoints(UnvisitedArea a, int att, List<double[]> geom, GminaIndex gminaIndex) {
        if (att >= 2) return List.of(); // wyczerpane warianty entry-pointu — pomiń
        double[][] samples = gminaIndex.samplePointsFor(a);
        double cx = a.lng(), cy = a.lat();
        if (att == 1) return List.of(new double[]{cx, cy}); // centroid
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
        final double MULT = 1.69; // haversine → effort proxy (jak w seedzie)
        final double BASE_COST = 2.5; // bazowy koszt każdego nowego waypointu (wjazd/wyjazd, krzywizna)
        int filled = 0;
        for (int pass = 0; pass < 6; pass++) {
            // Adaptive HOLE_R: gdy seed pasmo niskie (80-85%) i densify nie dopina, rozszerzamy promień
            // szukania dziur w późnych passach. pass 0-1: 6 km, 2-3: 10 km, 4-5: 15 km. Łapie więcej
            // peryferyjnych dziur enclosed dla zwartego regionu (np. zwarta trasa w środku, dziurki bardziej rozproszone).
            final double HOLE_R = pass < 2 ? 6.0 : (pass < 4 ? 10.0 : 15.0);
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

    /** RUNDA 40: effort DOKŁADNY = realKm + alpha·accurateClimbM(CAŁA geometria z cache) — TA SAMA formuła co
     *  ROUTE-STATS (debugStats). Decyzje pasma (cycle exit/grow, holefill, TRIM) używają TEGO, nie
     *  {@link #routeEffortViaCache} (Σ przybliżonych per-leg climbów — sliced legi zaniżają climb po cięciu →
     *  decyzja widziała 92% gdy realnie 105% → przestrzał growa). 0 BRouter (cache + elewacja lokalna). */
    private double routeEffortAccurate(List<double[]> route, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter) {
        double km = routeRealKm(route, cache, brouter, profile, alphaKmPerMeter);
        double climb = accurateClimbM(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
        return km + alphaKmPerMeter * climb;
    }

    /** RUNDA 41: 2-opt z logiem (gdy debugGeoJson) — user „chcę wiedzieć kiedy to idzie". Mierzy TANIO przez
     *  haversine km (0 BRouter/elewacji → OK w pętlach growa). Etykieta `phase` = które miejsce i kiedy. */
    private void twoOptLogged(List<double[]> route, String phase) {
        if (!debugGeoJson) { Alns2LocalSearch.twoOpt(route); return; }
        double kmBefore = routeHaversineKm(route);
        int wp = route.size();
        Alns2LocalSearch.twoOpt(route);
        double kmAfter = routeHaversineKm(route);
        log.info("ALNS2 2-OPT [{}]: havKm {}→{} (Δ{}), wps={}", new Object[]{phase,
                Math.round(kmBefore), Math.round(kmAfter), Math.round(kmAfter - kmBefore), wp});
    }

    /** L2: TANI Σ haversine km kolejnych wierzchołków trasy (bez BRoutera) — baza estymatora effortu. */
    private double routeHaversineKm(List<double[]> route) {
        double hav = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            hav += velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
        }
        return hav;
    }

    /** ADMIN DEBUG: realny dystans trasy = Σ EdgeInfo.distanceKm z cache (cache-hity po routeEffortViaCache).
     *  Do ROUTE-STATS, by dist/effort były spójne z logami algorytmu (a nie haversine z geometrii). */
    private double routeRealKm(List<double[]> route, EdgeCache cache,
                               BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                               String profile, double alphaKmPerMeter) {
        double km = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            km += getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).distanceKm();
        }
        return km;
    }

    /** Przybliżona odległość (km) punktu {@code p} od odcinka {@code a→b}: min haversine po ~10 interp. punktach.
     *  Używane w RE-SNAP (idea B) — wybór entry-pointu gminy najbliższego lokalnej cięciwie prev→next (minimal dip). */
    private static double pointToSegmentKm(double[] p, double[] a, double[] b) {
        final int N = 10;
        double best = Double.MAX_VALUE;
        for (int k = 0; k <= N; k++) {
            double t = k / (double) N;
            double[] q = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            double d = velomarker.service.planning.WaypointSelector.haversineKm(p, q);
            if (d < best) best = d;
        }
        return best;
    }

    /** RUNDA 23: ANALITYCZNA odległość punkt→odcinek (km) — rzut prostopadły w płaszczyźnie equirectangular
     *  (lng × cos(lat)), clamp t∈[0,1], jeden sqrt, ZERO trygonometrii w pętli. Dokładny dla segmentów
     *  ~kilkudziesięciu m. Dla segmentu zawierającego punkt zwraca ≈0 → early-break w skanie anchora działa
     *  (vs 11-próbkowy pointToSegmentKm który nigdy nie schodził ≤1e-7 → pełny skan O(...×11) = 10-min CPU). */
    private static double pointToSegmentExactKm(double[] p, double[] a, double[] b) {
        double latRad = Math.toRadians((a[1] + b[1]) / 2.0);
        double kx = 111.320 * Math.cos(latRad), ky = 110.574;
        double ax = a[0] * kx, ay = a[1] * ky, bx = b[0] * kx, by = b[1] * ky, px = p[0] * kx, py = p[1] * ky;
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 <= 1e-12 ? 0.0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = px - (ax + dx * t), ey = py - (ay + dy * t);
        return Math.sqrt(ex * ex + ey * ey);
    }

    // ── ADMIN DEBUG: GeoJSON snapshot trasy per faza (do paste-to-map w mapie rysowania) ──────────────
    // Guarded flagą debugGeoJson (default OFF). Loguje JEDNĄ linię GEOJSON-DEBUG [faza] = {FeatureCollection}.
    // User: breakpoint/kopiuj z konsoli → wklej na mapę. Szkielet (waypointy+numery) na fazach pośrednich,
    // realna geometria (debugGeometry) na końcu.

    /** Szkielet: LineString kolejnych waypointów + ponumerowane Pointy (props idx). */
    private void debugSkeleton(String phase, List<double[]> route) {
        if (!debugGeoJson || route == null || route.isEmpty()) return;
        StringBuilder sb = new StringBuilder(route.size() * 48);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        // 1) LineString trasy
        sb.append("{\"type\":\"Feature\",\"properties\":{\"phase\":\"").append(phase)
                .append("\",\"kind\":\"skeleton\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        appendCoords(sb, route);
        sb.append("]}}");
        // 2) ponumerowane Pointy
        for (int i = 0; i < route.size(); i++) {
            double[] p = route.get(i);
            sb.append(",{\"type\":\"Feature\",\"properties\":{\"idx\":").append(i)
                    .append(",\"phase\":\"").append(phase).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            appendPt(sb, p);
            sb.append("]}}");
        }
        sb.append("]}");
        System.out.println("dupa");
       // log.warn("GEOJSON-DEBUG [{}] n={}: {}", new Object[]{phase, route.size(), sb});
        System.out.println("pupa");
    }

    /** Realna geometria: pojedynczy LineString (np. finalna trasa po BRouterze). */
    private void debugGeometry(String phase, List<double[]> geometry) {
        debugGeometry(phase, geometry, null, -1);
    }

    private void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints) {
        debugGeometry(phase, geometry, waypoints, -1);
    }

    /** Realna geometria (LineString) + opcjonalnie ponumerowane waypointy (Pointy z idx) NA tej trasie.
     *  realKm = autorytatywny dystans (BRouter/cache); jeśli ≤0 → ROUTE-STATS liczy haversine z geometrii. */
    private void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (!debugGeoJson || geometry == null || geometry.size() < 2) return;
        StringBuilder sb = new StringBuilder(geometry.size() * 24);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"phase\":\"")
                .append(phase).append("\",\"kind\":\"geometry\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        appendCoords(sb, geometry);
        sb.append("]}}");
        if (waypoints != null) {
            for (int i = 0; i < waypoints.size(); i++) {
                sb.append(",{\"type\":\"Feature\",\"properties\":{\"idx\":").append(i)
                        .append(",\"phase\":\"").append(phase).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
                appendPt(sb, waypoints.get(i));
                sb.append("]}}");
            }
        }
        sb.append("]}");
        System.out.println("dupa");
//        log.info("GEOJSON-DEBUG [{}] pts={} wps={}: {}",
//                new Object[]{phase, geometry.size(), waypoints == null ? 0 : waypoints.size(), sb});
        debugStats(phase, geometry, waypoints, realKm);
        if (waypoints != null) debugSkeleton(phase, waypoints); // RUNDA 24: skeleton ZAWSZE obok debugGeometry (życzenie usera)
        System.out.println("pupa");
    }

    /** ADMIN DEBUG: zwięzła linia metryk trasy dla fazy — dystans/wznios/effort vs budżet + gminy per kategoria. */
    private void debugStats(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (geometry == null || geometry.size() < 2) return;
        int wps = waypoints == null ? 0 : waypoints.size();
        double km = realKm; // autorytatywny (BRouter/cache) — spójny z effortem algorytmu
        if (km <= 0) {       // fallback: haversine z geometrii (gdy brak realKm)
            km = 0;
            for (int i = 0; i < geometry.size() - 1; i++) {
                km += velomarker.service.planning.WaypointSelector.haversineKm(geometry.get(i), geometry.get(i + 1));
            }
        }
        double climb = accurateClimbM(geometry);
        double effort = km + params.alphaKmPerMeter() * climb;
        String budgetStr = debugBudget > 0
                ? Math.round(effort) + "/" + Math.round(debugBudget) + " (" + Math.round(effort * 100.0 / debugBudget) + "%)"
                : "n/a";
        String areasStr = "n/a";
        int total = 0, deep = 0;
        String noDeepStr = "";
        if (debugGminaIndex != null) {
            Map<Integer, Integer> visits = countVisitsPerArea(geometry, debugGminaIndex);
            total = visits.size(); // pełny wielokąt (findGminaForPoint) — DOTKNIĘTE, w tym muśnięte (<200m)
            Map<String, Integer> byCat = new java.util.TreeMap<>();
            for (Integer id : visits.keySet()) {
                byCat.merge(debugAreaCat != null ? debugAreaCat.getOrDefault(id, "?") : "?", 1, Integer::sum);
            }
            areasStr = byCat.toString();
            // RUNDA 61: drugi licznik = ≥200m kredyt (bufor −200, visitedAreaIds) — uczciwy. + lista gmin pokrytych
            // ≥200m BEZ głębokiego wp (findCreditedGminaForPoint) = zaliczonych tylko tranzytem/muśnięciem, bez kotwicy.
            Set<Integer> deepCov = debugGminaIndex.visitedAreaIds(geometry);
            deep = deepCov.size();
            if (waypoints != null) {
                Set<Integer> wpDeep = new HashSet<>();
                for (double[] wp : waypoints) {
                    UnvisitedArea a = debugGminaIndex.findCreditedGminaForPoint(wp[0], wp[1]);
                    if (a != null) wpDeep.add(a.areaId());
                }
                Map<Integer, String> idName = new HashMap<>();
                for (double[] p : geometry) {
                    UnvisitedArea a = debugGminaIndex.findGminaForPoint(p[0], p[1]);
                    if (a != null) idName.putIfAbsent(a.areaId(), a.name());
                }
                List<String> noDeep = new ArrayList<>();
                for (int gid : deepCov) if (!wpDeep.contains(gid)) noDeep.add(idName.getOrDefault(gid, "id" + gid));
                noDeep.sort(null);
                noDeepStr = " | bez-głębokiego-wp(≥200m, " + noDeep.size() + "): " + noDeep;
            }
        }
        // RUNDA 40 (A): gminy z >1 wp (łamanie inwariantu 1 wp/gmina). Mapuj każdy wp → gmina (findGminaForPoint), tally.
        String dupStr = "n/a";
        if (waypoints != null && debugGminaIndex != null) {
            Map<Integer, Integer> wpPerArea = new HashMap<>();
            Map<Integer, String> areaName = new HashMap<>();
            for (double[] wp : waypoints) {
                UnvisitedArea a = debugGminaIndex.findGminaForPoint(wp[0], wp[1]);
                if (a == null) continue;
                wpPerArea.merge(a.areaId(), 1, Integer::sum);
                areaName.putIfAbsent(a.areaId(), a.name());
            }
            List<String> dups = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : wpPerArea.entrySet())
                if (e.getValue() > 1) dups.add(areaName.get(e.getKey()) + "(" + e.getValue() + ")");
            dups.sort(null);
            dupStr = dups.size() + " " + dups;
        }
        log.info("ROUTE-STATS [{}]: dist={} km, climb={} m, effort={} budżetu, wps={}, gminy={} (≥200m: {}) {} | dup-wp={}{}",
                new Object[]{phase, Math.round(km), Math.round(climb), budgetStr, wps, total, deep, areasStr, dupStr, noDeepStr});
    }

    private static void appendCoords(StringBuilder sb, List<double[]> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            appendPt(sb, pts.get(i));
            sb.append(']');
        }
    }

    private static void appendPt(StringBuilder sb, double[] p) {
        sb.append(String.format(java.util.Locale.ROOT, "%.6f,%.6f", p[0], p[1]));
    }

    /** ADMIN DEBUG: złóż realną geometrię całej trasy z EdgeCache (geometria per leg z BRoutera). Wołane
     *  TYLKO gdy cache krawędzi ciepły (po routeEffortViaCache) → cache-hity, brak nowych calli BRoutera. */
    private List<double[]> concatRealGeometry(List<double[]> route, EdgeCache cache,
                                              BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                              String profile, double alphaKmPerMeter) {
        List<double[]> geom = new ArrayList<>();
        for (int i = 0; i < route.size() - 1; i++) {
            List<double[]> seg = getEdge(route.get(i), route.get(i + 1), cache, brouter, profile, alphaKmPerMeter).geometry();
            if (seg == null || seg.isEmpty()) continue;
            int from = geom.isEmpty() ? 0 : 1; // pomiń zdublowany wspólny wierzchołek styku legów
            for (int j = from; j < seg.size(); j++) geom.add(seg.get(j));
        }
        return geom;
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
        if (route.size() < 3) return;
        List<double[][]> edges = new ArrayList<>(route.size() - 1);
        for (int i = 0; i < route.size() - 1; i++) edges.add(new double[][]{route.get(i), route.get(i + 1)});
        prewarmPairs(edges, cache, brouter, profile, alphaKmPerMeter);
    }

    /** Równoległy pre-warm DOWOLNEJ listy par A→B (dedup po kierunkowym kluczu). Bound = semafor
     *  brouterParallelism. Reuse: prewarmEdges (kolejne pary route) + tailPrune v2 (eDirect/nEdge pary). */
    private void prewarmPairs(List<double[][]> pairs, EdgeCache cache,
                              BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                              String profile, double alphaKmPerMeter) {
        // Proxy-search: getEdge liczy haversine×grid (tani), a leniwa kalibracja chce 1 realnego
        // probe'a NA komórkę — równoległy pre-warm wywołałby thundering-herd (N edge'y w świeżej
        // komórce → N realnych probe'ów naraz). Sekwencyjny getEdge w eval = 1 probe/komórkę. Skip.
        if (proxyGrid != null) return;
        if (brouterParallelism <= 1 || pairs == null || pairs.isEmpty()) return;
        // Unikalne krawędzie (dedup po kierunkowym kluczu) — każdą policz raz.
        List<double[][]> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (double[][] p : pairs) {
            String k = String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", p[0][0], p[0][1], p[1][0], p[1][1]);
            if (seen.add(k)) edges.add(p);
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
                return computeEdgeProxy(pts, cache, brouter, profile, alphaKmPerMeter);
            }
            List<Waypoint> wps = List.of(
                    new Waypoint(pts[0][0], pts[0][1], null),
                    new Waypoint(pts[1][0], pts[1][1], null));
            try {
                RouteCalculation calc = brouter.apply(wps, profile);
                cache.onRealCall(); // v3.16: realny strzał (real-mode) — księgowanie per powód
                double km = calc.distanceKm();
                double climbM = 0;
                if (elevation != null) {
                    // Pełna granulacja — bez tego edge >500 coords (długie krawędzie) ucinały climb.
                    try { climbM = elevation.sample(calc.coordinates(), calc.coordinates().size()).gainM(); }
                    catch (RuntimeException ignored) {}
                }
                return new EdgeCache.EdgeInfo(km, climbM, km + alphaKmPerMeter * climbM, calc.coordinates());
            } catch (RuntimeException e) {
                cache.onRealCall(); // v3.16: strzał rzucił (wyspa) — i tak był realnym wywołaniem BRoutera
                // BRouter nie policzył (target-island / brak drogi) → zapamiętaj jako wyspę,
                // żeby seed prune usunął ten waypoint przed finalnym chunked BRouterem (inaczej
                // finalny call wywala się twardo na klastrze wysp → plan FAILED).
                // RUNDA 47: loguj POWÓD+GDZIE (throttled per krawędź) + zliczaj per powód (agregat po seedzie).
                boolean first = failedEdges.add(edgeKey(pts[0], pts[1]));
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String reason = brouterFailReason(msg);
                brouterFailReasons.merge(reason, 1, Integer::sum);
                if (first) log.warn("ALNS2 BRouter-FAIL [{}] @ {},{} → {},{} : {}", new Object[]{reason,
                        pts[0][0], pts[0][1], pts[1][0], pts[1][1], msg.length() > 120 ? msg.substring(0, 120) : msg});
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
    private EdgeCache.EdgeInfo computeEdgeProxy(double[][] pts, EdgeCache cache,
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
                cache.onRealCall(); // v3.16: realny strzał (probe kalibracji proxy) — księgowanie per powód
                double km = calc.distanceKm();
                double climbM = 0;
                if (elevation != null) {
                    // Pełna granulacja — proxy mode też nie może zaniżać climb dla probe'a kalibracji.
                    try { climbM = elevation.sample(calc.coordinates(), calc.coordinates().size()).gainM(); }
                    catch (RuntimeException ignored) {}
                }
                proxyGrid.recordReal(cell, km, hav, climbM);
                return new EdgeCache.EdgeInfo(km, climbM, km + alpha * climbM, calc.coordinates());
            } catch (RuntimeException e) {
                cache.onRealCall(); // v3.16: probe rzucił — realny strzał i tak nastąpił
                // Probe nie przeszedł — sygnał wyspy (best-effort; reszta wysp wyjdzie na finale).
                failedEdges.add(edgeKey(pts[0], pts[1]));
            }
        }
        double km = hav * proxyGrid.fDist(cell);
        double climb = km * proxyGrid.fClimbPerKm(cell);
        proxyGrid.recordProxyUse(cell);
        return new EdgeCache.EdgeInfo(km, climb, km + alpha * climb, List.of(pts[0], pts[1]));
    }

    /** RUNDA 50: egzekwuj INWARIANT 1 wp/gmina po cięciu. Duplikaty bierze inline-re-kotwica (@1683), gdy stary wp
     *  leży na granicy bufora −200 (findCreditedGminaForPoint=null) → re-kotwica dokłada drugi, a SHALLOW-CLEAN (które
     *  by usunęło) jest zagated tylko dla entry-anchora. Tu: grupuj nie-anchor wp po findGminaForPoint, dla gmin z >1
     *  zostaw JEDEN kredytujący (gmina nie spada), usuń resztę (scal prev→next + prewarm, jak SHALLOW-CLEAN). */
    private int dedupeOneWpPerGmina(List<double[]> route, List<SeedSel> selected, List<double[]> anchors,
                                    EdgeCache cache, BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                    String profile, double alphaKmPerMeter, GminaIndex gminaIndex) {
        Map<Integer, List<double[]>> byGmina = new HashMap<>();
        for (double[] p : route) {
            if (isAnchor(p, anchors)) continue;
            UnvisitedArea g = gminaIndex.findGminaForPoint(p[0], p[1]);
            if (g == null) continue;
            byGmina.computeIfAbsent(g.areaId(), k -> new ArrayList<>()).add(p);
        }
        List<double[]> toRemove = new ArrayList<>();
        int dupGminy = 0;
        for (Map.Entry<Integer, List<double[]>> e : byGmina.entrySet()) {
            List<double[]> wps = e.getValue();
            if (wps.size() <= 1) continue;
            dupGminy++;
            // RUNDA 53: zostaw KREDYTUJĄCY o MIN detourze (przelot, nie ogonek). Detour = nadłożenie nogi przez wp.
            double[] keep = null; double keepDetour = Double.MAX_VALUE;
            for (double[] p : wps) {
                UnvisitedArea cg = gminaIndex.findCreditedGminaForPoint(p[0], p[1]);
                if (cg == null || cg.areaId() != e.getKey()) continue;
                int pi = identityIndexOf(route, p);
                double det = (pi > 0 && pi < route.size() - 1)
                        ? velomarker.service.planning.WaypointSelector.haversineKm(route.get(pi - 1), p)
                          + velomarker.service.planning.WaypointSelector.haversineKm(p, route.get(pi + 1))
                          - velomarker.service.planning.WaypointSelector.haversineKm(route.get(pi - 1), route.get(pi + 1))
                        : 0.0;
                if (det < keepDetour) { keepDetour = det; keep = p; }
            }
            if (keep == null) keep = wps.get(0);
            for (double[] p : wps) if (p != keep) toRemove.add(p);
        }
        if (toRemove.isEmpty()) return 0;
        List<double[][]> mp = new ArrayList<>();
        for (double[] p : toRemove) {
            int di = identityIndexOf(route, p);
            if (di > 0 && di < route.size() - 1) mp.add(new double[][]{route.get(di - 1), route.get(di + 1)});
        }
        for (double[] p : toRemove) {
            int di = identityIndexOf(route, p);
            if (di >= 0) { route.remove(di); selected.removeIf(s -> s.point() == p); }
        }
        cache.setReason("ogonek-scalenie");
        prewarmPairs(mp, cache, brouter, profile, alphaKmPerMeter);
        cache.setReason("pomiar");
        log.info("ALNS2 DEDUPE-WP: usunięto {} duplikatów (gmin z >1 wp: {})", new Object[]{toRemove.size(), dupGminy});
        return toRemove.size();
    }

    /** Kierunkowy klucz krawędzi A→B (5 miejsc, ~1m) — spójny z EdgeCache. */
    private static String edgeKey(double[] a, double[] b) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
    }

    /** RUNDA 47: klasyfikacja komunikatu błędu BRoutera na powód (do logu/agregatu). */
    private static String brouterFailReason(String msg) {
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("island")) return "target-island";
        if (m.contains("not mapped") || m.contains("datafile") || m.contains("not found")) return "not-mapped";
        if (m.contains("timeout") || m.contains("killed") || m.contains("watchdog")) return "timeout";
        return "other";
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
