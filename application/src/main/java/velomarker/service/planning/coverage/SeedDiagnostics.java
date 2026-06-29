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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Raportowanie wyniku seeda (podsumowanie + najdłuższe legi). Oddzielone od orkiestracji SeedBuilder. */
final class SeedDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(SeedDiagnostics.class);
    private static final int LEG_TOPK = 15;

    private final EdgeRouter edgeRouter;
    private final CoverageAreaIndex coverageAreaIndex;

    SeedDiagnostics(SeedContext ctx) {
        this.edgeRouter = ctx.edgeRouter();
        this.coverageAreaIndex = ctx.coverageAreaIndex();
    }

    void logSeedSummary(int selectedSize, int routeSize, int totalPruned, int trimmed, int densified,
                        int totalRetried, double realEffort, double targetEffort, long seedStartTs) {
        log.info("Coverage seed ({}): +{} obszarów, removed={} islands, trimmed={}, grow-near={}, retried={} entry-points, real effort={}/{} ({}%) [v3.22: InitGrowPhase + FinalizePhase], route size={}",
                new Object[]{"distBase-sort", selectedSize, totalPruned, trimmed, densified, totalRetried,
                        Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), routeSize});
        long seedWallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        Map<String, Long> byReason = edgeRouter.realCallsByReason();
        log.info("Coverage STRZAŁY/plan (seed wall={}s, realne brouter.apply per powód): grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} inne={} | RAZEM realnych={} (misses={}; różnica = sliced-seedy bez BRoutera)",
                new Object[]{seedWallS,
                        byReason.getOrDefault("grow", 0L),
                        byReason.getOrDefault("ogonek-relokacja", 0L),
                        byReason.getOrDefault("ogonek-scalenie", 0L),
                        byReason.getOrDefault("dziura-otoczona", 0L),
                        byReason.getOrDefault("dziura-przy-trasie", 0L),
                        byReason.getOrDefault("pomiar", 0L),
                        byReason.getOrDefault("inne", 0L),
                        edgeRouter.realCalls(), edgeRouter.misses()});
    }

    void logTopLongLegs(List<double[]> route) {
        List<Integer> legIdx = new ArrayList<>();
        for (int i = 0; i < route.size() - 1; i++) legIdx.add(i);
        legIdx.sort((x, y) -> Double.compare(
                velomarker.service.planning.WaypointSelector.haversineKm(route.get(y), route.get(y + 1)),
                velomarker.service.planning.WaypointSelector.haversineKm(route.get(x), route.get(x + 1))));
        StringBuilder legSb = new StringBuilder();
        for (int t = 0; t < Math.min(LEG_TOPK, legIdx.size()); t++) {
            int i = legIdx.get(t);
            double havKm = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
            EdgeInfo e = edgeRouter.edge(route.get(i), route.get(i + 1));
            Set<Integer> gset = new HashSet<>();
            for (double[] p : e.geometry()) {
                UnvisitedArea a = coverageAreaIndex.findGminaForPoint(p[0], p[1]);
                if (a != null) gset.add(a.areaId());
            }
            legSb.append(String.format(java.util.Locale.ROOT, " [hav=%.0f real=%.0f gmin=%d]",
                    havKm, e.distanceKm(), gset.size()));
        }
        log.info("Coverage seed top-{} długich legów (hav/real km, #gmin kredytowanych):{}", LEG_TOPK, legSb);
    }
}
