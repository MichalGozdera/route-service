package velomarker.service.planning.coverage.seed;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Naprawa wysp (islands-fix): usuwa/relokuje waypointy nieosiągalne BRouterem. Wydzielone z InitGrowPhase. */
final class IslandFixer {

    private static final Logger log = LoggerFactory.getLogger(IslandFixer.class);
    private static final int MAX_PASSES = 3;

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final CoverageAreaIndex coverageAreaIndex;
    private final SeedOps ops;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final List<double[]> anchors;
    private final List<double[]> baseline;

    private final Map<Integer, Integer> entryAttempt = new HashMap<>();
    private final Map<String, UnvisitedArea> gminaPointCache = new HashMap<>();
    private final Function<double[], UnvisitedArea> findGminaCached;
    private long tEvalNs, tVisitsNs, tPruneNs;

    IslandFixer(SeedContext ctx, SeedRoute seed) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.coverageAreaIndex = ctx.coverageAreaIndex();
        this.ops = ctx.ops();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.anchors = seed.anchors();
        this.baseline = seed.baseline();
        this.findGminaCached = pt -> gminaPointCache.computeIfAbsent(
                String.format(java.util.Locale.ROOT, "%.6f,%.6f", pt[0], pt[1]),
                k -> coverageAreaIndex.findGminaForPoint(pt[0], pt[1]));
    }

    IslandFixResult run() {
        double realEffort = 0;
        int pruned = 0, retried = 0;
        for (int islPass = 0; islPass < MAX_PASSES; islPass++) {
            long _tEval = System.nanoTime();
            EvalResult ev = metrics.eval(route);
            tEvalNs += System.nanoTime() - _tEval;
            realEffort = ev.effort();
            long _tVis = System.nanoTime();
            Map<Integer, Integer> visits = SeedBuilder.countVisitsPerArea(ev.geometry(), coverageAreaIndex);
            tVisitsNs += System.nanoTime() - _tVis;
            Set<double[]> toRemove = new HashSet<>();
            boolean swapped = false;
            int unreachable = 0;
            long _tPrune = System.nanoTime();
            for (int i = 1; i < route.size() - 1; i++) {
                double[] cur = route.get(i);
                if (GeometryUtil.isAnchor(cur, anchors)) continue;
                UnvisitedArea g = findGminaCached.apply(cur);
                int gv = (g == null) ? 0 : visits.getOrDefault(g.areaId(), 0);
                boolean island = edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(route.get(i - 1), cur))
                        || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(cur, route.get(i + 1)));
                if (gv != 0 && !island) continue;
                unreachable++;
                if (g == null) {
                    toRemove.add(cur);
                    continue;
                }
                int id = g.areaId();
                int att = entryAttempt.getOrDefault(id, 0);
                double[] alt = null;
                if (att == 0) {
                    alt = coverageAreaIndex.deepestInteriorPoint(g.areaId());
                    if (alt == null) alt = new double[]{g.lng(), g.lat()};
                } else if (att == 1) {
                    alt = new double[]{g.lng(), g.lat()};
                }
                if (alt != null && (alt[0] != cur[0] || alt[1] != cur[1])) {
                    route.set(i, alt);
                    ops.swapEntry(selected, cur, alt, baseline);
                    swapped = true;
                    retried++;
                    entryAttempt.put(id, att + 1);
                } else if (att <= 1) {
                    entryAttempt.put(id, att + 1);
                } else {
                    toRemove.add(cur);
                }
            }
            tPruneNs += System.nanoTime() - _tPrune;
            if (toRemove.isEmpty() && !swapped) break;
            selected.removeIf(s -> toRemove.contains(s.point()));
            route.removeIf(toRemove::contains);
            pruned += toRemove.size();
            CoverageLocalSearch.optimize(route);
            realEffort = metrics.effortViaCache(route);
            log.info("Coverage seed islands pass {}: removed={}, unreachable={}, retried={} entry-points (total)",
                    new Object[]{islPass, toRemove.size(), unreachable, retried});
        }
        logBreakdown();
        return new IslandFixResult(realEffort, pruned, retried, false);
    }

    private void logBreakdown() {
        log.info("Coverage ISLANDS-FIX BREAKDOWN: eval={}s | countVisits={}s | prune={}s",
                new Object[]{tEvalNs / 1_000_000_000L, tVisitsNs / 1_000_000_000L, tPruneNs / 1_000_000_000L});
    }
}
