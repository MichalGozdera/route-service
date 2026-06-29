package velomarker.service.planning.coverage;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

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

// Planner pokrycia: maksymalizacja zaliczonych gmin przy budżecie efortu (seed + finalize).
public class CoveragePlanner {

    private static final Logger log = LoggerFactory.getLogger(CoveragePlanner.class);

    private final CoveragePlannerParameters params;
    private final ElevationDataSource elevation;
    private final Random rand;
    private final int brouterParallelism;
    private final AreaCoverageIndexFactory coverageFactory;
    private final SpatialIndexFactory spatialIndexFactory;
    private final boolean debugGeoJson;

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

    public CoverageResult plan(List<UnvisitedArea> candidatePool,
                             List<UnvisitedArea> historicallyVisited,
                             List<double[]> baselineGeom,
                             RoutePreferences prefs,
                             String profile,
                             BrouterFn brouter,
                             Consumer<Boolean> snapToggle,
                             Map<Integer, String> areaRodzajName,
                             velomarker.service.planning.PlanTraceSink traceSink,
                             PlanTimings timings) {
        long startTs = System.currentTimeMillis();
        CoverageEngine eng = buildEngine(candidatePool, historicallyVisited, prefs, profile, brouter, snapToggle, areaRodzajName, traceSink);

        SeedStart start = buildInitialRoute(prefs, baselineGeom, brouter, profile);
        List<double[]> route = start.route();

        log.info("Coverage greedy seed: budget effort={}", Math.round(eng.totalLimit()));
        eng.seedBuilder().greedySeedRoute(route, start.anchors(), eng.totalLimit(), params.alphaKmPerMeter(), start.baseline(), timings);

        return assembleResult(eng, candidatePool, prefs, profile, brouter, route, start.brouterCalls(), startTs);
    }

    private CoverageEngine buildEngine(List<UnvisitedArea> candidatePool, List<UnvisitedArea> historicallyVisited,
                                       RoutePreferences prefs, String profile, BrouterFn brouter, Consumer<Boolean> snapToggle,
                                       Map<Integer, String> areaRodzajName,
                                       velomarker.service.planning.PlanTraceSink traceSink) {
        List<UnvisitedArea> histVisited = historicallyVisited != null ? historicallyVisited : List.of();
        double totalLimit = computeBudgetAndLog(prefs, candidatePool.size());

        Set<Integer> histVisitedIds = new HashSet<>();
        for (UnvisitedArea a : histVisited) histVisitedIds.add(a.areaId());
        CoverageAreaIndex coverageAreaIndex = new CoverageAreaIndex(candidatePool,
                coverageFactory.build(candidatePool, histVisited), spatialIndexFactory, histVisitedIds);
        log.info("Coverage init: candidates={} + historycznie zaliczone (sąsiedztwo)={}",
                new Object[]{candidatePool.size(), histVisited.size()});
        HilbertOrdering ordering = new HilbertOrdering();
        ordering.computeBbox(candidatePool);
        EdgeRouter edgeRouter = new EdgeRouter(brouter, profile, params.alphaKmPerMeter(), elevation, brouterParallelism);
        RouteMetrics metrics = new RouteMetrics(edgeRouter, coverageAreaIndex, elevation, params.alphaKmPerMeter());
        Map<String, Double> rewards = RewardModel.rewardPerCategory(candidatePool, spatialIndexFactory);
        Map<Integer, String> areaCat = new HashMap<>();
        for (UnvisitedArea a : candidatePool) {
            areaCat.put(a.areaId(), RewardModel.formatCategoryKey(RewardModel.categoryKey(a)));
        }

        SeedOps ops = new SeedOps(ordering);
        CoverageDebug debug = new CoverageDebug(debugGeoJson, edgeRouter, metrics, coverageAreaIndex, params, totalLimit,
                areaCat, areaRodzajName != null ? areaRodzajName : Map.of(), traceSink);
        SeedContext ctx = new SeedContext(edgeRouter, metrics, coverageAreaIndex, ordering, candidatePool, rewards,
                debug, ops, debugGeoJson, snapToggle);
        SeedBuilder seedBuilder = new SeedBuilder(ctx);
        return new CoverageEngine(coverageAreaIndex, edgeRouter, metrics, seedBuilder, areaCat, totalLimit);
    }

    private CoverageResult assembleResult(CoverageEngine eng, List<UnvisitedArea> candidatePool, RoutePreferences prefs,
                                          String profile, BrouterFn brouter, List<double[]> route, int seedBrouterCalls,
                                          long startTs) {
        RouteMetrics metrics = eng.metrics();
        EdgeRouter edgeRouter = eng.edgeRouter();
        CoverageAreaIndex coverageAreaIndex = eng.index();
        double totalLimit = eng.totalLimit();

        EvalResult seedEval = metrics.eval(route);
        int brouterCalls = seedBrouterCalls + (int) edgeRouter.realCalls();
        Set<Integer> currentVisited = seedEval.visited();
        List<Waypoint> bestWps = buildWaypoints(route, prefs);
        logAfterSeed(eng, route, seedEval.effort(), currentVisited);

        List<double[]> seedRealGeom = metrics.realGeometry(route);
        double seedRealKm = metrics.realKm(route);
        RouteCalculation bestCalc = new RouteCalculation(seedRealGeom, seedRealKm);
        Set<Integer> bestVisited = coverageAreaIndex.visitedAreaIds(bestCalc.coordinates());
        if (bestVisited.isEmpty()) bestVisited = currentVisited;
        List<UnvisitedArea> visitedAreas = collectVisited(candidatePool, bestVisited);

        log.info("Coverage coverage breakdown: {}", RewardModel.breakdown(bestVisited, eng.areaCat()));
        HoleDiagnostics.log(bestCalc, candidatePool, coverageAreaIndex, bestVisited);

        double finalEffort = bestCalc.distanceKm() + params.alphaKmPerMeter() * metrics.climbM(bestCalc.coordinates());
        log.info("Coverage done: bestVisited={} bestEffort={}/{} (~{}%) brouterCalls={} cacheHits={} cacheRatio={}% elapsedMs={}",
                new Object[]{visitedAreas.size(),
                        Math.round(finalEffort), Math.round(totalLimit),
                        Math.round(finalEffort * 100.0 / totalLimit), brouterCalls,
                        edgeRouter.hits(), Math.round(edgeRouter.hitRatio() * 100), System.currentTimeMillis() - startTs});

        RouteCalculation withStats = recomputeWithStats(bestWps, profile, brouter);
        if (withStats != null) {
            bestCalc = new RouteCalculation(seedRealGeom, seedRealKm, List.of(), withStats.stats(), null, null);
            brouterCalls++;
        }
        if (debugGeoJson) {
            List<double[]> finalWps = new ArrayList<>(bestWps.size());
            for (Waypoint w : bestWps) finalWps.add(new double[]{w.lng(), w.lat()});
            eng.seedBuilder().debugGeometry("final-real", bestCalc.coordinates(), finalWps, bestCalc.distanceKm());
        }
        return new CoverageResult(bestCalc, bestWps, visitedAreas, brouterCalls);
    }

    private void logAfterSeed(CoverageEngine eng, List<double[]> route, double currentEffort, Set<Integer> currentVisited) {
        double accAfterSeed = eng.metrics().effortAccurate(route);
        log.info("Coverage after seed (+2opt): route_size={} effort={}/{} ({}%, dokładny; wewn.przybliżony={}) visited={}",
                new Object[]{route.size(), Math.round(accAfterSeed), Math.round(eng.totalLimit()),
                        Math.round(accAfterSeed * 100.0 / eng.totalLimit()), Math.round(currentEffort),
                        currentVisited.size()});
        EdgeRouter edgeRouter = eng.edgeRouter();
        if (!edgeRouter.failReasons().isEmpty())
            log.info("Coverage BRouter-FAILS (seed): {} | unikalnych krawędzi z failem={}",
                    new Object[]{edgeRouter.failReasons(), edgeRouter.failedEdges().size()});
    }

    private SeedStart buildInitialRoute(RoutePreferences prefs, List<double[]> baselineGeom,
                                        BrouterFn brouter, String profile) {
        List<Waypoint> initialWps = BaselineComputer.buildAnchorWaypoints(prefs);
        int brouterCalls = 0;
        List<double[]> baseline;
        if (baselineGeom != null && baselineGeom.size() >= 2) {
            baseline = GeometryUtil.downsample(baselineGeom, 200);
        } else {
            RouteCalculation initialCalc = brouter.route(initialWps, profile, false);
            brouterCalls = 1;
            baseline = GeometryUtil.downsample(initialCalc.coordinates(), 200);
        }
        List<double[]> anchors = new ArrayList<>();
        for (Waypoint w : initialWps) anchors.add(w.toLngLat());
        return new SeedStart(new ArrayList<>(anchors), anchors, baseline, brouterCalls);
    }

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

    private double computeBudgetAndLog(RoutePreferences prefs, int candidateCount) {
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : 200;
        int elevPerDay = prefs.elevationPerDayM() != null ? prefs.elevationPerDayM() : 0;
        int days = prefs.days() != null ? prefs.days() : 1;
        double totalLimit = (kmPerDay + params.alphaKmPerMeter() * elevPerDay) * days;
        log.info("Coverage init: budget effort={} (= {} km + {} × {} m × {} days), candidates={}",
                new Object[]{Math.round(totalLimit), kmPerDay,
                        params.alphaKmPerMeter(), elevPerDay, days, candidateCount});
        return totalLimit;
    }

    private static List<UnvisitedArea> collectVisited(List<UnvisitedArea> candidatePool, Set<Integer> visitedIds) {
        List<UnvisitedArea> out = new ArrayList<>();
        for (UnvisitedArea a : candidatePool) {
            if (visitedIds.contains(a.areaId())) out.add(a);
        }
        return out;
    }

    public static List<Waypoint> buildWaypoints(List<double[]> route, RoutePreferences prefs) {
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
