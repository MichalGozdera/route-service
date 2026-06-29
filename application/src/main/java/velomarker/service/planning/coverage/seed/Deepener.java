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

import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pogłębianie płytko dotkniętych gmin (push wejścia ku wnętrzu / MIC). Wydzielone z SpurCutter — etap różny od cięcia. */
final class Deepener {

    private static final int MAX_PUSH_LVL = 3;
    private static final int MAX_ITER = 6;

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final CoverageAreaIndex coverageAreaIndex;
    private final SeedOps ops;
    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final List<double[]> baseline;
    private final Set<Integer> deepAnchorAreaIds;
    private final Set<Integer> settledAreas;
    private final Map<Integer, double[]> origWp;
    private final Map<Integer, UnvisitedArea> idToArea;

    private final Map<Integer, Integer> deepenLevel = new HashMap<>();
    private Map<Integer, double[]> entryMap;
    private Map<Integer, double[]> roundWp;
    private Map<Integer, double[]> deepestMap;
    private List<double[]> realTrackForPush;

    Deepener(SeedContext ctx, SeedRoute seed, Set<Integer> deepAnchorAreaIds, Set<Integer> settledAreas,
             Map<Integer, double[]> origWp, Map<Integer, UnvisitedArea> idToArea) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.coverageAreaIndex = ctx.coverageAreaIndex();
        this.ops = ctx.ops();
        this.route = seed.route();
        this.selected = seed.selected();
        this.baseline = seed.baseline();
        this.deepAnchorAreaIds = deepAnchorAreaIds;
        this.settledAreas = settledAreas;
        this.origWp = origWp;
        this.idToArea = idToArea;
    }

    void deepen(Map<Integer, double[]> entryMap) {
        this.entryMap = entryMap;
        deepenLevel.clear();
        boolean again = true;
        int iter = 0;
        while (again && iter < MAX_ITER) {
            iter++;
            edgeRouter.prewarm(route);
            realTrackForPush = metrics.realGeometry(route);
            Set<Integer> shallow = computeShallow(realTrackForPush);
            if (shallow.isEmpty()) break;
            for (int gid : shallow) {
                int lvl = deepenLevel.getOrDefault(gid, 0);
                deepenLevel.put(gid, lvl < MAX_PUSH_LVL ? lvl + 1 : -1);
            }
            roundWp = buildRoundWp();
            List<Integer> pushGids = shallow.stream().filter(origWp::containsKey).collect(Collectors.toList());
            deepestMap = coverageAreaIndex.deepestPointsOnTrack(realTrackForPush, new HashSet<>(pushGids));
            List<Decision> pushes = edgeRouter.parallelMap(pushGids, this::computePush);
            again = applyPushes(pushes);
        }
    }

    private Map<Integer, double[]> buildRoundWp() {
        Map<Integer, double[]> m = new HashMap<>();
        for (SeedSel s : selected) m.putIfAbsent(s.area().areaId(), s.point());
        return m;
    }

    private Set<Integer> computeShallow(List<double[]> realTrack) {
        Set<Integer> deeply = coverageAreaIndex.deeplyVisitedAreaIds(realTrack);
        Set<Integer> shallow = new HashSet<>();
        for (int gid : coverageAreaIndex.touchedAreaIds(realTrack))
            if (!deeply.contains(gid) && !deepAnchorAreaIds.contains(gid) && origWp.containsKey(gid)
                    && !settledAreas.contains(gid))
                shallow.add(gid);
        return shallow;
    }

    private Decision computePush(int gid) {
        double[] cur = roundWp.get(gid);
        if (cur == null) return null;
        int lvl = deepenLevel.getOrDefault(gid, 0);
        if (lvl < 0) return shallowDec(gid, origWp.get(gid), cur, Source.RESTORE);
        double[] entry = entryMap.get(gid);
        if (lvl == 1) {
            double[] deepest = deepestMap.get(gid);
            if (entry != null && deepest != null && GeometryUtil.hav(entry, deepest) > 0.001)
                return shallowDec(gid, GeometryUtil.extendBeyond(entry, deepest, 80.0), cur, Source.PUSH);
        }
        if (lvl >= 3) {
            double[] probe = probeSample(gid, cur);
            if (probe != null) return shallowDec(gid, probe, cur, Source.PUSH);
        }
        double[] base = entry != null ? entry : cur;
        double[] cel = GeometryUtil.movePointTowards(base, coverageAreaIndex.deepestInteriorPoint(gid), lvl * 80.0);
        return shallowDec(gid, cel, cur, Source.PUSH);
    }

    private double[] probeSample(int gid, double[] cur) {
        UnvisitedArea area = idToArea.get(gid);
        if (area == null) return null;
        int idx = GeometryUtil.identityIndexOf(route, cur);
        if (idx <= 0 || idx >= route.size() - 1) return null;
        double[] prev = route.get(idx - 1), next = route.get(idx + 1);
        for (double[] s : coverageAreaIndex.samplePointsFor(area)) {
            if (edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(prev, s))
                    || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(s, next))) continue;
            List<double[]> in = edgeRouter.edge(prev, s).geometry();
            List<double[]> out = edgeRouter.edge(s, next).geometry();
            List<double[]> seg = new ArrayList<>(in.size() + out.size());
            seg.addAll(in);
            seg.addAll(out);
            double[] d = coverageAreaIndex.firstTrackPointAtDepth(seg, gid, 220.0);
            if (d != null) return d;
        }
        return null;
    }

    private boolean applyPushes(List<Decision> pushes) {
        boolean changed = false;
        for (Decision d : pushes) {
            if (d.target() == null) continue;
            int idx = GeometryUtil.identityIndexOf(route, d.keepWp());
            if (idx <= 0 || idx >= route.size() - 1) continue;
            double[] cel = d.target().clone();
            ops.swapEntry(selected, d.keepWp(), cel, baseline);
            route.set(idx, cel);
            changed = true;
        }
        return changed;
    }

    private static Decision shallowDec(int gid, double[] cel, double[] cur, Source src) {
        return new Decision(gid, Kind.SHALLOW, cel, cur, List.of(), src);
    }
}
