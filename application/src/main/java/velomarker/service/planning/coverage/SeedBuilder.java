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

import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Orkiestrator budowy seeda pokrycia: baseline-projection + fazy InitGrowPhase → FinalizePhase. Instancja per plan.
public final class SeedBuilder {

    private final SeedContext ctx;
    private final CoverageDebug debug;
    private final RouteMetrics metrics;
    private final boolean debugGeoJson;

    public SeedBuilder(SeedContext ctx) {
        this.ctx = ctx;
        this.debug = ctx.debug();
        this.metrics = ctx.metrics();
        this.debugGeoJson = ctx.debugGeoJson();
    }

    public void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        debug.geometry(phase, geometry, waypoints, realKm);
    }

    public void greedySeedRoute(List<double[]> route, List<double[]> anchors, double targetEffort, double alphaKmPerMeter,
                         List<double[]> baseline, PlanTimings timings) {
        final double ROAD_FACTOR = 1.3;
        final double CLIMB_PER_KM_ESTIMATE = 3.0;
        final double EFFORT_MULTIPLIER = ROAD_FACTOR * (1.0 + CLIMB_PER_KM_ESTIMATE * alphaKmPerMeter);

        int bn = baseline.size();
        double[] baseCum = new double[bn];
        for (int i = 1; i < bn; i++) {
            baseCum[i] = baseCum[i - 1] + velomarker.service.planning.WaypointSelector.haversineKm(
                    baseline.get(i - 1), baseline.get(i));
        }
        List<double[]> anchorOnly = new ArrayList<>(route);
        List<SeedSel> selected = new ArrayList<>();
        SeedRoute seed = new SeedRoute(route, selected, anchorOnly, anchors, baseline, baseCum);
        final double hiBand = targetEffort * 1.05;
        final double growCeiling = targetEffort * 1.10;
        long seedStartTs = System.currentTimeMillis();

        CandidatePicker picker = new CandidatePicker(ctx, seed);

        long tPick = System.currentTimeMillis();
        IslandFixResult ig = new InitGrowPhase(
                ctx, seed, picker, targetEffort, hiBand, growCeiling, EFFORT_MULTIPLIER, seedStartTs).run();
        timings.addPickingMs(System.currentTimeMillis() - tPick);
        double realEffort = ig.realEffort();
        int totalPruned = ig.pruned();
        int totalRetried = ig.retried();

        debug.resetShots();
        if (debugGeoJson) debug.logShots("init-grow");

        long tFinal = System.currentTimeMillis();
        FinalizeResult fr = new FinalizePhase(
                ctx, seed, picker, targetEffort, hiBand, growCeiling, realEffort, ig.allCandidatesUsed()).run();
        timings.addFinalizeMs(System.currentTimeMillis() - tFinal);
        realEffort = fr.realEffort();
        if (debugGeoJson) debug.logShots("seed-final");
        int densified = fr.grown();
        debug.skeleton("seed", route);
        debug.geometry("seed-real", metrics.realGeometry(route), route, metrics.realKm(route));

        SeedDiagnostics diag = new SeedDiagnostics(ctx);
        diag.logSeedSummary(selected.size(), route.size(), totalPruned, fr.trimmed(), densified, totalRetried,
                realEffort, targetEffort, seedStartTs);
        diag.logTopLongLegs(route);
    }

    public static Map<Integer, Integer> countVisitsPerArea(List<double[]> geometry, CoverageAreaIndex index) {
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
}
