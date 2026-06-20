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
import velomarker.port.out.planning.SpatialIndexFactory;

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
    /** Fabryka JTS coverage indexu (PEŁNA geometria, plain intersect) — budowana per plan() nad pulą. */
    private final AreaCoverageIndexFactory coverageFactory;
    /** Fabryka indeksu przestrzennego (STRtree) — sąsiedztwo/reward/density per plan(). */
    private final SpatialIndexFactory spatialIndexFactory;
    /** ADMIN DEBUG: gdy true, loguje GeoJSON snapshot trasy na każdej fazie seeda. application.yml: planning.coverage.debug-geojson. */
    private final boolean debugGeoJson;

    /** Planner pokrycia — porządkowanie obszarów krzywą Hilberta (space-filling). */
    public CoveragePlanner(CoveragePlannerParameters params, ElevationDataSource elevation,
                                int brouterParallelism,
                                AreaCoverageIndexFactory coverageFactory, SpatialIndexFactory spatialIndexFactory,
                                boolean debugGeoJson) {
        this.params = params;
        this.elevation = elevation;
        this.rand = new Random(42);
        this.brouterParallelism = Math.max(1, brouterParallelism);
        this.coverageFactory = coverageFactory;
        this.spatialIndexFactory = spatialIndexFactory;
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
        GminaIndex gminaIndex = new GminaIndex(candidatePool, coverageFactory.build(candidatePool), spatialIndexFactory);
        EdgeRouter edgeRouter = new EdgeRouter(brouter, profile, params.alphaKmPerMeter(), elevation, brouterParallelism);
        RouteMetrics metrics = new RouteMetrics(edgeRouter, gminaIndex, elevation, params.alphaKmPerMeter());
        // Bbox kandydatów — dla Hilbert space-filling. Liczony raz.
        HilbertOrdering ordering = new HilbertOrdering();
        ordering.computeBbox(candidatePool);

        // Reward per kategoria (Iter 11 Fix 2: NN-distance proportion, logged inside)
        Map<String, Double> rewards = RewardModel.rewardPerCategory(candidatePool, spatialIndexFactory);
        // areaId → human kategoria (do per-iter coverage breakdown w logach)
        Map<Integer, String> areaCat = new HashMap<>();
        for (UnvisitedArea a : candidatePool) {
            areaCat.put(a.areaId(), RewardModel.formatCategoryKey(RewardModel.categoryKey(a)));
        }

        // Silnik budowy seeda — kolaboratory wstrzyknięte; mutuje route w miejscu.
        SeedBuilder seedBuilder = new SeedBuilder(gminaIndex, candidatePool, rewards, edgeRouter, metrics,
                ordering, elevation, params, debugGeoJson, totalLimit, areaCat);

        // Start = anchory (start→via→end) + baseline (korytarz). Reużywamy baseline z orkiestracji gdy podany.
        SeedStart start = buildInitialRoute(prefs, baselineGeom, brouter, profile);
        List<double[]> route = start.route(), anchors = start.anchors(), baseline = start.baseline();
        int brouterCalls = start.brouterCalls();

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
        seedBuilder.greedySeedRoute(route, anchors, seedTarget, params.alphaKmPerMeter(), baseline);

        // BEZ post-seed twoOpt/relocate (v3.3): seed kończy się po pełnym 2opt ostatniego cyklu
        // COMPACT-LOOP; późniejsze przetasowanie tworzyło NIEPOSPRZĄTANE ogonki (wp133 — relocate
        // po ostatnim tailPrune, nikt już nie czyścił). Trasa z seeda = finalna kolejność.

        // EVAL przez EdgeCache (per-edge, NIE pełny chunked BRouter). Pierwsza ewaluacja populuje
        // cache wszystkich ~N krawędzi; kolejne iteracje SA liczą tylko ZMIENIONE krawędzie.
        RouteMetrics.EvalResult seedEval = metrics.eval(route);
        brouterCalls += (int) edgeRouter.realCalls(); // v3.18: realne brouter.apply (nie misses — te liczą sliced-seedy)

        double currentEffort = seedEval.effort();
        Set<Integer> currentVisited = seedEval.visited();

        List<double[]> bestRoute = new ArrayList<>(route);
        double bestEffort = currentEffort;
        Set<Integer> bestVisitedIds = currentVisited;
        List<Waypoint> bestWps = buildWaypoints(route, prefs);

        // RUNDA 40: pokaż effort DOKŁADNY (spójny z ROUTE-STATS seed-real) jako główny; w nawiasie wewn. przybliżony
        // (Σ per-leg climb, evalRoute) używany dalej w reconcile. To rozwiewa „111 czy 101%": realny ślad = dokładny.
        double accAfterSeed = metrics.effortAccurate(route);
        log.info("Coverage after seed (+2opt): route_size={} effort={}/{} ({}%, dokładny; wewn.przybliżony={}) visited={}",
                new Object[]{route.size(), Math.round(accAfterSeed), Math.round(totalLimit),
                        Math.round(accAfterSeed * 100.0 / totalLimit), Math.round(currentEffort),
                        currentVisited.size()});
        if (!edgeRouter.failReasons().isEmpty()) // RUNDA 47: agregat failów BRoutera po seedzie (per powód + ile krawędzi)
            log.info("Coverage BRouter-FAILS (seed): {} | unikalnych krawędzi z failem={}",
                    new Object[]{edgeRouter.failReasons(), edgeRouter.failedEdges().size()});

        int accepted = 0;
        int rejected = 0;
        int iter = 0;

        // FINAL: jeden REALNY chunked BRouter na best (target-island handling, dokładna geometria, dystans).
        RouteCalculation bestCalc = finalChunkedRoute(bestWps, bestRoute, brouter, profile, metrics);
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

        log.info("Coverage coverage breakdown: {}", RewardModel.breakdown(bestVisited, areaCat));

        logHoleDiagnostics(bestCalc, candidatePool, gminaIndex, bestVisited);

        // FINAL effort = REAL po wszystkich reconcile iter'ach (km z BRoutera + alpha × wznios oknami).
        // Stara wartość `bestEffort` zostawała z seeda przed reconcile → user widział 101% w logu
        // gdy faktycznie reconcile dociągnął np. do 110%. Liczymy raz na koniec dla prawdziwego obrazu.
        double finalEffort = bestCalc.distanceKm() + params.alphaKmPerMeter() * metrics.climbM(bestCalc.coordinates());
        long elapsedMs = System.currentTimeMillis() - startTs;
        log.info("Coverage done: bestVisited={} bestEffort={}/{} (~{}%) iters={} brouterCalls={} cacheHits={} cacheRatio={}% accepted={} rejected={} elapsedMs={}",
                new Object[]{visitedAreas.size(),
                        Math.round(finalEffort), Math.round(totalLimit),
                        Math.round(finalEffort * 100.0 / totalLimit), iter, brouterCalls,
                        edgeRouter.hits(), Math.round(edgeRouter.hitRatio() * 100),
                        accepted, rejected, elapsedMs});

        // FINAL RECOMPUTE: jeden ostatni call z brouterFinal (computeStats=true) → pełne stats (surface/road/
        // spans) dla orchestratora; wewnętrzne ~10k calle szły z computeStats=false (oszczędność CPU).
        RouteCalculation withStats = recomputeWithStats(bestWps, profile, brouterFinal);
        if (withStats != null) { bestCalc = withStats; brouterCalls++; }
        // ADMIN DEBUG: finalna realna geometria trasy + ponumerowane waypointy
        if (debugGeoJson) {
            List<double[]> finalWps = new ArrayList<>(bestWps.size());
            for (Waypoint w : bestWps) finalWps.add(new double[]{w.lng(), w.lat()});
            seedBuilder.debugGeometry("final-real", bestCalc.coordinates(), finalWps, bestCalc.distanceKm());
        }
        return new CoverageResult(bestCalc, bestWps, visitedAreas, iter, brouterCalls, accepted, rejected);
    }

    /** Trasa startowa seeda: anchory (start→via→end) + route (kopia anchorów) + baseline (korytarz) + ile BRouter calli. */
    private record SeedStart(List<double[]> route, List<double[]> anchors, List<double[]> baseline, int brouterCalls) {}

    /** Zbuduj start: waypointy start→via→end → anchory + wąska route; baseline z orkiestracji (reużycie) lub świeży BRouter. */
    private SeedStart buildInitialRoute(RoutePreferences prefs, List<double[]> baselineGeom,
                                        BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile) {
        List<Waypoint> initialWps = new ArrayList<>();
        initialWps.add(prefs.start());
        if (prefs.via() != null) initialWps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) initialWps.add(prefs.start());
        else if (prefs.end() != null) initialWps.add(prefs.end());
        else initialWps.add(prefs.start());
        int brouterCalls = 0;
        List<double[]> baseline;
        if (baselineGeom != null && baselineGeom.size() >= 2) {
            baseline = GeometryUtil.downsample(baselineGeom, 200); // reużycie baseline z orkiestracji (oszczędza ~50s/Alpy)
        } else {
            RouteCalculation initialCalc = brouter.apply(initialWps, profile); // degenerat → świeży routing
            brouterCalls = 1;
            baseline = GeometryUtil.downsample(initialCalc.coordinates(), 200);
        }
        List<double[]> anchors = new ArrayList<>();
        for (Waypoint w : initialWps) anchors.add(w.toLngLat());
        return new SeedStart(new ArrayList<>(anchors), anchors, baseline, brouterCalls);
    }

    /** Finalny REALNY chunked BRouter na best; gdy padnie (klaster wysp) — fallback do per-edge geometrii (haversine). */
    private RouteCalculation finalChunkedRoute(List<Waypoint> bestWps, List<double[]> bestRoute,
                                               BiFunction<List<Waypoint>, String, RouteCalculation> brouter,
                                               String profile, RouteMetrics metrics) {
        try {
            return brouter.apply(bestWps, profile);
        } catch (RuntimeException ex) {
            log.warn("Coverage final chunked BRouter failed ({}) — fallback do per-edge geometrii", ex.getMessage());
            List<double[]> geom = metrics.eval(bestRoute).geometry();
            double km = 0;
            for (int i = 1; i < geom.size(); i++) {
                km += velomarker.service.planning.WaypointSelector.haversineKm(geom.get(i - 1), geom.get(i));
            }
            return new RouteCalculation(geom, km);
        }
    }

    /** Ostatni call z brouterFinal (computeStats=true) → RouteCalculation z pełnymi stats; null gdy padł (zwracamy bez stats). */
    private RouteCalculation recomputeWithStats(List<Waypoint> bestWps, String profile,
                                                BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal) {
        try {
            RouteCalculation finalBestCalc = brouterFinal.apply(bestWps, profile);
            log.info("Coverage final recompute z stats: distanceKm={} stats.totalMeters={}",
                    new Object[]{Math.round(finalBestCalc.distanceKm()),
                            finalBestCalc.stats() != null ? finalBestCalc.stats().totalMeters() : 0});
            return finalBestCalc;
        } catch (RuntimeException ex) {
            log.warn("Coverage final recompute z stats failed ({}) — zwracam wynik bez stats", ex.getMessage());
            return null;
        }
    }

    /** DIAGNOSTYKA DZIUR (log-only): nieodwiedzone gminy wg odległości od finalnej trasy — pokazuje
     *  czy dziury są blisko (do odzyskania) czy daleko/nieroutowalne (prawdziwe wyspy). */
    private void logHoleDiagnostics(RouteCalculation bestCalc, List<UnvisitedArea> candidatePool, GminaIndex gminaIndex,
                                    Set<Integer> bestVisited) {
        List<double[]> diagGeom = GeometryUtil.subsampleGeometry(bestCalc.coordinates(), 4000);
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
    }

    // ── SCORE & EFFORT ──────────────────────────────────────────────────────────────────




    // ── Anchors ─────────────────────────────────────────────────────────────────────────


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
