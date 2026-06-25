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
                             List<UnvisitedArea> historicallyVisited,
                             List<double[]> baselineGeom,
                             RoutePreferences prefs,
                             String profile,
                             BrouterFn brouter,
                             Consumer<Boolean> snapToggle,
                             Consumer<UUID> checkCancel) {
        long startTs = System.currentTimeMillis();
        List<UnvisitedArea> histVisited = historicallyVisited != null ? historicallyVisited : List.of();

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
        // Historycznie zaliczone wchodzą do indeksu TYLKO jako sąsiedztwo (domykanie dziur + zgranie z dawnym pokryciem).
        java.util.Set<Integer> histVisitedIds = new HashSet<>();
        for (UnvisitedArea a : histVisited) histVisitedIds.add(a.areaId());
        GminaIndex gminaIndex = new GminaIndex(candidatePool,
                coverageFactory.build(candidatePool, histVisited), spatialIndexFactory, histVisitedIds);
        log.info("Coverage init: candidates={} + historycznie zaliczone (sąsiedztwo)={}",
                new Object[]{candidatePool.size(), histVisited.size()});
        HilbertOrdering ordering = new HilbertOrdering();
        ordering.computeBbox(candidatePool);
        EdgeRouter edgeRouter = new EdgeRouter(brouter, profile, params.alphaKmPerMeter(), elevation, brouterParallelism);
        RouteMetrics metrics = new RouteMetrics(edgeRouter, gminaIndex, elevation, params.alphaKmPerMeter());
        // Reward per kategoria (Iter 11 Fix 2: NN-distance proportion, logged inside)
        Map<String, Double> rewards = RewardModel.rewardPerCategory(candidatePool, spatialIndexFactory);
        // areaId → human kategoria (do per-iter coverage breakdown w logach)
        Map<Integer, String> areaCat = new HashMap<>();
        for (UnvisitedArea a : candidatePool) {
            areaCat.put(a.areaId(), RewardModel.formatCategoryKey(RewardModel.categoryKey(a)));
        }

        // Silnik budowy seeda — kolaboratory wstrzyknięte; mutuje route w miejscu.
        SeedBuilder seedBuilder = new SeedBuilder(gminaIndex, candidatePool, rewards, edgeRouter, metrics,
                ordering, elevation, params, debugGeoJson, totalLimit, areaCat, snapToggle);

        // Start = anchory (start→via→end) + baseline (korytarz). Reużywamy baseline z orkiestracji gdy podany.
        SeedStart start = buildInitialRoute(prefs, baselineGeom, brouter, profile);
        List<double[]> route = start.route(), anchors = start.anchors(), baseline = start.baseline();
        int brouterCalls = start.brouterCalls();


        double seedTarget = totalLimit;
        log.info("Coverage greedy seed: budget effort={} (v3.8: init-grow + compact-loop ≤8 cykli: grow→2opt→anchor→enclosed→tailPrune[in-memory]→topup)",
                new Object[]{Math.round(seedTarget)});
        seedBuilder.greedySeedRoute(route, anchors, seedTarget, params.alphaKmPerMeter(), baseline);

        RouteMetrics.EvalResult seedEval = metrics.eval(route);
        brouterCalls += (int) edgeRouter.realCalls(); // v3.18: realne brouter.apply (nie misses — te liczą sliced-seedy)

        double currentEffort = seedEval.effort();
        Set<Integer> currentVisited = seedEval.visited();

        List<double[]> bestRoute = new ArrayList<>(route);
        double bestEffort = currentEffort;
        Set<Integer> bestVisitedIds = currentVisited;
        List<Waypoint> bestWps = buildWaypoints(route, prefs);

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

        // D: seed-ślad JEST finalny (po reroute A-C zero sliced → realny edge-by-edge). BEZ osobnego chunked-BRoutera
        // — chunked liczył całość naraz i dawał INNY ślad niż seed edge-by-edge (243→242 gmin, dist +1km). Teraz
        // final ≡ seed: gminy/dist spójne z planowaniem. Geometria z cache (0 BRouter — reroute już policzył legi).
        List<double[]> seedRealGeom = metrics.realGeometry(bestRoute);
        double seedRealKm = metrics.realKm(bestRoute);
        RouteCalculation bestCalc = new RouteCalculation(seedRealGeom, seedRealKm);
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

        // FINAL RECOMPUTE: jeden ostatni call z computeStats=true → pełne stats (surface/road/
        // spans) dla orchestratora; wewnętrzne ~10k calle szły z computeStats=false (oszczędność CPU).
        // Stats (surface/road/smoothness) z JEDNEGO chunked-calla — ale GEOMETRIA zostaje z seed (spójna z coverage);
        // flatSpans puste, bo to indeksy chunked-geometrii, nie pasują do seed-geom.
        RouteCalculation withStats = recomputeWithStats(bestWps, profile, brouter);
        if (withStats != null) {
            bestCalc = new RouteCalculation(seedRealGeom, seedRealKm, java.util.List.of(), withStats.stats(), null, null);
            brouterCalls++;
        }
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
                                        BrouterFn brouter, String profile) {
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
            RouteCalculation initialCalc = brouter.route(initialWps, profile, false); // degenerat → świeży routing
            brouterCalls = 1;
            baseline = GeometryUtil.downsample(initialCalc.coordinates(), 200);
        }
        List<double[]> anchors = new ArrayList<>();
        for (Waypoint w : initialWps) anchors.add(w.toLngLat());
        return new SeedStart(new ArrayList<>(anchors), anchors, baseline, brouterCalls);
    }

    /** Ostatni call z computeStats=true → RouteCalculation z pełnymi stats; null gdy padł (zwracamy bez stats). */
    private RouteCalculation recomputeWithStats(List<Waypoint> bestWps, String profile,
                                                BrouterFn brouter) {
        try {
            RouteCalculation finalBestCalc = brouter.route(bestWps, profile, true);
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
