package velomarker.service.planning.coverage;

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
 * Coverage: Orienteering / Max Coverage Path Solver.
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
public class CoveragePlanner {

    private static final Logger log = LoggerFactory.getLogger(CoveragePlanner.class);

    private final CoveragePlannerParameters params;
    private final ElevationDataSource elevation;
    private final Random rand;
    /** Ile krawędzi BRouter liczyć RÓWNOLEGLE (pre-warm). = route.calculate.max-concurrent. */
    private final int brouterParallelism;
    /** Bbox centroidów kandydatów (ustawiany per plan()) — do Hilbert space-filling. */
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
    /** ADMIN DEBUG: gdy true, loguje GeoJSON snapshot trasy na każdej fazie seeda (init/batch/prune/densify/
     *  reconcile/final). Domyślnie OFF — per-batch zalewa log. application.yml: planning.coverage.debug-geojson. */
    private final boolean debugGeoJson;
    // ADMIN DEBUG: kontekst do linii ROUTE-STATS (budżet/gminy/kategorie) — stashowany per plan() gdy debugGeoJson.
    private double debugBudget;
    private GminaIndex debugGminaIndex;
    private Map<Integer, String> debugAreaCat;

    /** Planner pokrycia — porządkowanie obszarów krzywą Hilberta (space-filling). */
    public CoveragePlanner(CoveragePlannerParameters params, ElevationDataSource elevation,
                                int brouterParallelism,
                                AreaCoverageIndexFactory coverageFactory, boolean debugGeoJson) {
        this.params = params;
        this.elevation = elevation;
        this.rand = new Random(42);
        this.brouterParallelism = Math.max(1, brouterParallelism);
        this.coverageFactory = coverageFactory;
        this.debugGeoJson = debugGeoJson;
    }

    /** Wynik plannera pokrycia. */
    public record CoverageResult(
            RouteCalculation calc,
            List<Waypoint> finalWaypoints,
            List<UnvisitedArea> visited,
            int iterations,
            int brouterCalls,
            int accepted,
            int rejected
    ) {}

    public CoverageResult plan(UUID taskId,
                             List<UnvisitedArea> candidatePool,
                             List<double[]> baselineGeom,
                             RoutePreferences prefs,
                             String profile,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                             BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal,
                             Consumer<UUID> checkCancel) {
        long startTs = System.currentTimeMillis();

        // Effort budget
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : 200;
        int elevPerDay = prefs.elevationPerDayM() != null ? prefs.elevationPerDayM() : 0;
        int days = prefs.days() != null ? prefs.days() : 1;
        double dailyLimit = kmPerDay + params.alphaKmPerMeter() * elevPerDay;
        double totalLimit = dailyLimit * days;

        log.info("Coverage init: budget effort={} (= {} km + {} × {} m × {} days), candidates={}",
                new Object[]{Math.round(totalLimit), kmPerDay,
                        params.alphaKmPerMeter(), elevPerDay, days, candidatePool.size()});

        // Index + cache. Coverage (zaliczenia) liczy JTS na PEŁNEJ geometrii (plain intersect jak front).
        GminaIndex gminaIndex = new GminaIndex(candidatePool, coverageFactory.build(candidatePool));
        EdgeCache cache = new EdgeCache();
        failedEdges.clear();
        brouterFailReasons.clear();
        // Bbox kandydatów — dla Hilbert space-filling. Liczony raz.
        computeCandidateBbox(candidatePool);

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
        log.info("Coverage greedy seed: budget effort={} (v3.8: init-grow + compact-loop ≤8 cykli: grow→2opt→anchor→enclosed→tailPrune[in-memory]→topup)",
                new Object[]{Math.round(seedTarget)});
        greedySeedRoute(route, anchors, gminaIndex, candidatePool, rewardPerCategory,
                seedTarget, params.alphaKmPerMeter(), baseline,
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

        List<double[]> bestRoute = new ArrayList<>(route);
        double bestEffort = currentEffort;
        Set<Integer> bestVisitedIds = currentVisited;
        List<Waypoint> bestWps = buildWaypoints(route, prefs);

        // RUNDA 40: pokaż effort DOKŁADNY (spójny z ROUTE-STATS seed-real) jako główny; w nawiasie wewn. przybliżony
        // (Σ per-leg climb, evalRoute) używany dalej w reconcile. To rozwiewa „111 czy 101%": realny ślad = dokładny.
        double accAfterSeed = routeEffortAccurate(route, cache, brouter, profile, params.alphaKmPerMeter());
        log.info("Coverage after seed (+2opt): route_size={} effort={}/{} ({}%, dokładny; wewn.przybliżony={}) visited={}",
                new Object[]{route.size(), Math.round(accAfterSeed), Math.round(totalLimit),
                        Math.round(accAfterSeed * 100.0 / totalLimit), Math.round(currentEffort),
                        currentVisited.size()});
        if (!brouterFailReasons.isEmpty()) // RUNDA 47: agregat failów BRoutera po seedzie (per powód + ile krawędzi)
            log.info("Coverage BRouter-FAILS (seed): {} | unikalnych krawędzi z failem={}",
                    new Object[]{brouterFailReasons, failedEdges.size()});

        int accepted = 0;
        int rejected = 0;
        int iter = 0;

        // FINAL: jeden REALNY chunked BRouter na best (target-island handling, dokładna geometria,
        // dystans). SA używał per-edge eval (proxy); tu robimy "prawdziwy" wynik dla orchestration.
        RouteCalculation bestCalc;
        try {
            bestCalc = brouter.apply(bestWps, profile);
        } catch (RuntimeException ex) {
            // Finalny chunked BRouter wywalił się (klaster wysp mimo prune) — NIE failuj planu.
            // Zbuduj wynik z per-edge geometrii (evalRoute ma haversine fallback dla wysp, nie rzuca).
            log.warn("Coverage final chunked BRouter failed ({}) — fallback do per-edge geometrii", ex.getMessage());
            EvalResult fb = evalRoute(bestRoute, cache, brouter, profile, params.alphaKmPerMeter(), gminaIndex);
            List<double[]> geom = fb.geometry();
            double km = 0;
            for (int i = 1; i < geom.size(); i++) {
                km += velomarker.service.planning.WaypointSelector.haversineKm(geom.get(i - 1), geom.get(i));
            }
            bestCalc = new RouteCalculation(geom, km);
        }
        brouterCalls++;
        // STRICT count: gmina zaliczona dopiero gdy trasa wjeżdża ≥ requiredDepth W GŁĄB (nie otarcie
        // krawędzi). Eliminuje false-positives „dojazdów pod krawędź". Front (turf, uproszczony ~90m)
        // i tak nie liczy otarć → po tej zmianie front i backend się zbliżą.
        Set<Integer> bestVisited = gminaIndex.visitedAreaIds(bestCalc.coordinates());
        if (bestVisited.isEmpty()) bestVisited = bestVisitedIds; // fallback gdyby final calc pusty
        List<UnvisitedArea> visitedAreas = new ArrayList<>();
        for (UnvisitedArea a : candidatePool) {
            if (bestVisited.contains(a.areaId())) visitedAreas.add(a);
        }

        log.info("Coverage coverage breakdown: {}", breakdown(bestVisited, areaCat));

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
        log.info("Coverage hole diagnostics: {} dziur / {} pool → distToRoute 0-3km:{}, 3-6km:{}, 6-15km:{}, >15km:{}",
                new Object[]{totalHoles, candidatePool.size(), h0_3, h3_6, h6_15, h15_plus});
        log.info("Coverage hole names (≤60): {}", String.join(", ", holeNames));

        // FINAL effort = REAL po wszystkich reconcile iter'ach (km z BRoutera + alpha × wznios oknami).
        // Stara wartość `bestEffort` zostawała z seeda przed reconcile → user widział 101% w logu
        // gdy faktycznie reconcile dociągnął np. do 110%. Liczymy raz na koniec dla prawdziwego obrazu.
        double finalEffort = bestCalc.distanceKm() + params.alphaKmPerMeter() * accurateClimbM(bestCalc.coordinates());
        long elapsedMs = System.currentTimeMillis() - startTs;
        log.info("Coverage done: bestVisited={} bestEffort={}/{} (~{}%) iters={} brouterCalls={} cacheHits={} cacheRatio={}% accepted={} rejected={} elapsedMs={}",
                new Object[]{visitedAreas.size(),
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
            log.info("Coverage final recompute z stats: distanceKm={} stats.totalMeters={}",
                    new Object[]{Math.round(finalBestCalc.distanceKm()),
                            finalBestCalc.stats() != null ? finalBestCalc.stats().totalMeters() : 0});
            bestCalc = finalBestCalc;
            brouterCalls++;
        } catch (RuntimeException ex) {
            log.warn("Coverage final recompute z stats failed ({}) — zwracam wynik bez stats", ex.getMessage());
        }
        // ADMIN DEBUG: finalna realna geometria trasy + ponumerowane waypointy
        if (debugGeoJson) {
            List<double[]> finalWps = new ArrayList<>(bestWps.size());
            for (Waypoint w : bestWps) finalWps.add(new double[]{w.lng(), w.lat()});
            debugGeometry("final-real", bestCalc.coordinates(), finalWps, bestCalc.distanceKm());
        }
        return new CoverageResult(bestCalc, bestWps, visitedAreas, iter, brouterCalls, accepted, rejected);
    }

    // ── SCORE & EFFORT ──────────────────────────────────────────────────────────────────

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

    // ── Anchors ─────────────────────────────────────────────────────────────────────────

    private static boolean isAnchor(double[] p, List<double[]> anchors) {
        for (double[] a : anchors) {
            if (Math.abs(a[0] - p[0]) < 1e-6 && Math.abs(a[1] - p[1]) < 1e-6) return true;
        }
        return false;
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
                          List<double[]> baseline,
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
            double score = r / detourEffort;
            scored.add(new SeedSel(a, deep, orderKey(deep, baseline, baseCum), score, bestDist));
        }
        // SELEKCJA: najbliższe KORYTARZOWI start→meta pierwsze (distBase ASC) WAŻONE rewardem → gęsty
        // pas wzdłuż korytarza, rosnący na boki aż do budżetu. Klucz distBase/reward: blisko korytarza
        // + wysoki reward = pierwsze (rzadkie DE Kreis reward~2.3 nie zatapiane przez gęste CZ Obec
        // reward~0.23). Hilbert (orderKey) porządkuje trasę 2D. PL (jedna kategoria) → czysty distBase.
        scored.sort(Comparator.comparingDouble((SeedSel s) ->
                s.distBase() / Math.max(0.05, rewards.getOrDefault(rewardCategoryKey(s.area()), 1.0))));

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
        log.info("Coverage seed grow START: pool={} obszarów, target effort={} ({}/dzień × {}d)",
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
            log.info("Coverage seed round {} START: dotąd dodano {} obszarów, effort={}/{} ({}%), elapsed={}s",
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
                    CoverageLocalSearch.twoOptIncremental(route, route.size() - 1, 80);
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
                    log.info("Coverage seed progress: +{} obszarów (round={}), effort={}/{} ({}%), elapsed={}s, tempo={}ms/area, eta≈{}min",
                            new Object[]{selected.size(), round, Math.round(realEffort), Math.round(targetEffort),
                                    Math.round(pct), elapsedS, Math.round(avgSecPerArea * 1000), etaS / 60});
                }
                if (realEffort >= growCeiling) { // grow do 110% budżetu; COMPACT-LOOP/TRIM ściągnie do pasma [95,105]%
                    log.info("Coverage INIT-GROW: osiągnięto {}% (≥110%) → stop rundy 0",
                            Math.round(realEffort * 100.0 / targetEffort));
                    break;
                }
            }
            long roundDurMs = System.currentTimeMillis() - roundStartTs;
            log.info("Coverage seed round {} END: dodano {} obszarów w tej rundzie ({} → {}), trwało {}s",
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
                log.info("Coverage seed islands pass {}: removed={}, unreachable={}, retried={} entry-points (total)",
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
                log.info("Coverage COMPACT cycle {}: anchor-intersects → 2opt → BRouter → tailPrune → {} gmin @ {}% → KONIEC (w paśmie), calls={}",
                        new Object[]{cycle, gmin, Math.round(eFrac * 100), cache.realCalls() - cycleCallsStart});
                break;
            }
            // 7. >105% → przerwij; TRIM końcowy (poniżej) utnie najsłabsze
            if (eFrac > 1.05) {
                log.info("Coverage COMPACT cycle {}: {} gmin @ {}% > 105% → TRIM (poniżej pętli), calls={}",
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
            log.info("Coverage COMPACT cycle {}: anchor-intersects → 2opt → BRouter → tailPrune → {} gmin @ {}% → +{} (proporcja do 100%, cel +{}), calls={}",
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
                log.info("Coverage TRIM peel ti={} k={}: {} gmin @ {}% → -{} (reward/detour, fringe) → {}%, calls={}",
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
            log.info("Coverage HOLEFILL: 0 enclosed → KONIEC seeda (świeżo po anchor+2opt+podwójne cięcie)");
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
            log.info("Coverage HOLEFILL: {} enclosed → wp najgłębszy + anchor + 2opt + podwójne cięcie", enclosedHF.size());
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

        log.info("Coverage seed ({}): +{} obszarów, removed={} islands, trimmed={}, grow-near={}, retried={} entry-points, real effort={}/{} ({}%) [v3.8: init-grow + compact-loop(grow→2opt→anchor→enclosed→tailPrune→topup)], route size={}",
                new Object[]{"hilbert", selected.size(), totalPruned,
                        trimmed, densified, totalRetried,
                        Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), route.size()});
        // INSTRUMENTACJA: rozbicie wall-time seeda. routeEffort = BRouter (równoległy) + sumowanie;
        // rebuild/2opt/eval/visits/prune = NASZ single-thread (multithread go NIE przyspiesza).
        long seedWallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        log.info("Coverage seed PHASE BREAKDOWN (wall={}s): routeEffort(BRouter)={}s | rebuild={}s | 2opt={}s | eval={}s | countVisits={}s | prune={}s",
                new Object[]{seedWallS, tRouteEffortNs / 1_000_000_000L, tRebuildNs / 1_000_000_000L,
                        tTwoOptNs / 1_000_000_000L, tEvalNs / 1_000_000_000L, tVisitsNs / 1_000_000_000L,
                        tPruneNs / 1_000_000_000L});
        // L2: ile realnych checkpointów (zamiast real co batch), skalibrowany factor, sumaryczne calle BRoutera.
        log.info("Coverage seed L2: realCheckpoints={} effortFactor={} brouterCalls(real)={} (cache misses={} — w tym sliced-seedy)",
                new Object[]{realCheckpoints, String.format(java.util.Locale.ROOT, "%.3f", effortFactor), cache.realCalls(), cache.misses()});
        // v3.16: ROLLUP STRZAŁÓW per powód — realne brouter.apply (NIE misses: misses zawyża o sliced-seedy
        // z seedSlicedEdges, które tylko zasilają cache bez BRoutera). Odpowiedź na „ile strzałów po co".
        java.util.Map<String, Long> byReason = cache.realCallsByReason();
        log.info("Coverage STRZAŁY/plan (seed, realne brouter.apply per powód): grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} inne={} | RAZEM realnych={} (misses={}; różnica = sliced-seedy bez BRoutera)",
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
        log.info("Coverage seed top-{} długich legów (hav/real km, #gmin kredytowanych):{}", LEG_TOPK, legSb);
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
        log.info("Coverage STRZAŁY/plan [{}]: grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} | RAZEM={} (Δfaza={})",
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
                log.info("Coverage TAIL-PRUNE v6 [{}-cut{}]: relocated={}, reloc-skipped={}, passes={}, calls={}, effort {}→{} ({}%→{}%) | runda: reloc+{}, reroute={}, dropped-runda={} {} → {}",
                        new Object[]{debugPhase, round, relocated, relocSkipped, passes, cache.realCalls() - callsStart,
                                Math.round(effortBefore), Math.round(roundEffort),
                                Math.round(effortBefore * 100.0 / targetEffort), Math.round(roundEffort * 100.0 / targetEffort),
                                relocated - relRoundStart, rer, droppedRoundNames.size(), droppedRoundNames,
                                willContinue ? "kolejna runda" : "KONIEC pętli"});
                log.info("Coverage USUNIĘTE-OGONKI [{}-cut{}]: {} pozycji: {}",
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
        log.info("Coverage TAIL-PRUNE v6 (JTS-clean v2): relocated={}, reloc-skipped={}, passes={}, dropped={}, calls={}, effort {}→{} ({}%→{}%)",
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
        if (shown > 0) log.info("Coverage SPUR-ANATOMIA v6 [{}] ({} garbów ≥1km, top {}):{}",
                new Object[]{phase, kept.size(), Math.min(shown, 60), sb});
        else log.info("Coverage SPUR-ANATOMIA v6 [{}]: 0 garbów ≥1km (brak zaułków)", phase);
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
                        log.info("Coverage BATCH-GROW undo porcji {}: marginal={}/gminę > 3×ratio={} (dalekie gminy) → stop na {}% growTargetu",
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
        log.info("Coverage BATCH-GROW: batches={}, inserted={}, islands={}, checkpoints={}, refreshes={}, factor={}, calls={}, effort → {} ({}% growTarget)",
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
            log.info("Coverage ENCLOSED-FILL: domknięto={} dziur (8/8 otoczone), nieosiągalne={}, calls={}, effort → {} ({}%)",
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
            CoverageLocalSearch.twoOpt(route);
            // kredyt-verify: niezaliczona (most/wyspa) → retry centroid → usuń
            Set<Integer> vis = gminaIndex.visitedAreaIds(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            for (SeedSel s : new ArrayList<>(added)) {
                if (vis.contains(s.area().areaId())) continue;
                double[] c = new double[]{s.area().lng(), s.area().lat()};
                swapEntryPoint(selected, s.point(), c, baseline, baseCum);
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            CoverageLocalSearch.twoOpt(route);
            vis = gminaIndex.visitedAreaIds(concatRealGeometry(route, cache, brouter, profile, alphaKmPerMeter));
            for (SeedSel s : new ArrayList<>(added)) {
                if (!vis.contains(s.area().areaId())) { selected.remove(s); added.remove(s); }
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            CoverageLocalSearch.twoOpt(route);
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
            log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, wejście={} centroid={}, nowe-głębokie-bez-kotwicy={}",
                    new Object[]{debugPhase, iter, touched.size(), onBuffer, onCentroid, newDeepCount});
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iter, touchedCount, (System.nanoTime() - t0) / 1_000_000});
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


    private static double hav(double[] a, double[] b) {
        return velomarker.service.planning.WaypointSelector.haversineKm(a, b);
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
     * Klucz układania obszaru wzdłuż trasy — indeks na krzywej Hilberta (space-filling) nad bbox
     * kandydatów. Locality-preserving: sąsiednie indeksy = sąsiednie w 2D → grow zbiera ciągłą plamę
     * (nie rozsiane punkty), niezależnie od kształtu regionu (koło PL / korytarz DE-CZ).
     */
    private double orderKey(double[] p, List<double[]> baseline, double[] baseCum) {
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
        if (!debugGeoJson) { CoverageLocalSearch.twoOpt(route); return; }
        double kmBefore = routeHaversineKm(route);
        int wp = route.size();
        CoverageLocalSearch.twoOpt(route);
        double kmAfter = routeHaversineKm(route);
        log.info("Coverage 2-OPT [{}]: havKm {}→{} (Δ{}), wps={}", new Object[]{phase,
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

    /** EdgeInfo (z geometrią) dla A→B z cache; miss = 1 BRouter call (2-punktowy) + elevation. */
    private EdgeCache.EdgeInfo getEdge(double[] A, double[] B, EdgeCache cache,
                                       BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                       String profile, double alphaKmPerMeter) {
        return cache.getOrCompute(A[0], A[1], B[0], B[1], pts -> {
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
                if (first) log.warn("Coverage BRouter-FAIL [{}] @ {},{} → {},{} : {}", new Object[]{reason,
                        pts[0][0], pts[0][1], pts[1][0], pts[1][1], msg.length() > 120 ? msg.substring(0, 120) : msg});
                double hav = velomarker.service.planning.WaypointSelector.haversineKm(pts[0], pts[1]);
                return new EdgeCache.EdgeInfo(hav * 1.3, 0, hav * 1.3, List.of(pts[0], pts[1]));
            }
        });
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
        log.info("Coverage DEDUPE-WP: usunięto {} duplikatów (gmin z >1 wp: {})", new Object[]{toRemove.size(), dupGminy});
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
        final double REWARD_REFERENCE_DIST_KM = 10.0; // bazowa NN-dist (PL gmina NN ~7km → reward 0.7)
        Map<String, List<UnvisitedArea>> byCat = new HashMap<>();
        for (UnvisitedArea a : pool) {
            byCat.computeIfAbsent(rewardCategoryKey(a), k -> new ArrayList<>()).add(a);
        }
        Map<String, Double> reward = new HashMap<>();
        StringBuilder logSb = new StringBuilder();
        for (var e : byCat.entrySet()) {
            double nn = GminaIndex.avgNearestNeighborDistKm(e.getValue());
            double r = nn > 0 ? nn / REWARD_REFERENCE_DIST_KM : 1.0;
            r = Math.max(0.1, r);
            reward.put(e.getKey(), r);
            if (logSb.length() > 0) logSb.append(", ");
            logSb.append(formatCategoryKey(e.getKey())).append("=")
                    .append(String.format("%.2f", r))
                    .append(" (NN ").append(String.format("%.1f", nn)).append("km, n=")
                    .append(e.getValue().size()).append(")");
        }
        log.info("Coverage reward per category (refDist={}km): {{{}}}",
                new Object[]{REWARD_REFERENCE_DIST_KM, logSb});
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
