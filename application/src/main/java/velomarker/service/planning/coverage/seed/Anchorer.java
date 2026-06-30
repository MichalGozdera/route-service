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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// Kotwiczy dotknięte gminy waypointami i pogłębia trasę aż realny ślad wchodzi w każdą gminę.
public final class Anchorer {

    private static final Logger log = LoggerFactory.getLogger(Anchorer.class);

    private final CoverageAreaIndex coverageAreaIndex;
    private final RouteMetrics metrics;
    private final HilbertOrdering ordering;
    private final EdgeRouter edgeRouter;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final List<double[]> route;
    private final List<double[]> anchors;
    private final List<double[]> baseline;
    private final List<SeedSel> selected;
    private final String debugPhase;
    private final long startNs = System.nanoTime();
    private final Map<Integer, UnvisitedArea> areaById = new HashMap<>();
    private final Set<Integer> deepAnchorAreaIds = new HashSet<>();
    private final Map<Integer, double[]> sampleAnchor = new HashMap<>();
    private static final double REACHABLE_KM = 0.15;
    /** Progi głębokości kotwiczenia (m): bazowy {@code deepDepthM} z SeedContext (gminy 220, kafelki 70) +
     *  pochodne {@code deep2}/{@code deep3} proporcjonalne do gminowych 250/300 (gminy → 250/300, kafelki → 79.5/95.5). */
    private final double deepDepthM, deep2, deep3;
    private List<double[]> originalTrack;
    private Map<Integer, double[]> entryPointsFixed;
    private Set<Integer> touchedFixed;
    private Map<Integer, double[]> lvl1Map = Map.of();
    private final Map<Integer, Integer> deepenLevel = new HashMap<>();
    private Set<Integer> shallowAreaIds = new HashSet<>();
    private int iteration, touchedCount;
    private int fromSample, lvl0Count;
    private int deepenL1, deepenL2, deepenL3;

    public Anchorer(SeedContext ctx, SeedRoute seed, String debugPhase) {
        this.coverageAreaIndex = ctx.coverageAreaIndex();
        this.metrics = ctx.metrics();
        this.ordering = ctx.ordering();
        this.edgeRouter = ctx.edgeRouter();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.deepDepthM = ctx.deepDepthM();
        this.deep2 = deepDepthM * (250.0 / 220.0);
        this.deep3 = deepDepthM * (300.0 / 220.0);
        this.route = seed.route();
        this.selected = seed.selected();
        this.anchors = seed.anchors();
        this.baseline = seed.baseline();
        this.debugPhase = debugPhase;
        for (UnvisitedArea area : ctx.pool()) areaById.put(area.areaId(), area);
        for (double[] anchor : anchors) {
            UnvisitedArea anchorArea = coverageAreaIndex.findDeeplyCreditedGminaForPoint(anchor[0], anchor[1]);
            if (anchorArea != null) deepAnchorAreaIds.add(anchorArea.areaId());
        }
    }

    public void run() {
        boolean again = true;
        edgeRouter.rerouteApproximateLegs(route);
        originalTrack = metrics.realGeometry(route);
        entryPointsFixed = coverageAreaIndex.firstBufferEntryPoints(originalTrack);
        touchedFixed = coverageAreaIndex.touchedAreaIds(originalTrack);
        while (again && iteration < 8) {
            iteration++;
            anchorTouchedAreas();
            edgeRouter.prewarm(route);
            List<double[]> realTrack = metrics.realGeometry(route);
            computeShallow(realTrack);
            int probed = probeSamples();
            boolean progress = escalateShallow();
            again = progress || probed > 0;
            debug.geometry(debugPhase + "-anchor-iter" + iteration, realTrack, route, metrics.realKm(route));
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iteration, touchedCount, (System.nanoTime() - startNs) / 1_000_000});
    }

    private void anchorTouchedAreas() {
        touchedCount = touchedFixed.size();
        Set<Integer> lvl1Gids = new HashSet<>();
        for (int gid : touchedFixed)
            if (deepenLevel.getOrDefault(gid, 0) == 1 && !deepAnchorAreaIds.contains(gid) && !sampleAnchor.containsKey(gid))
                lvl1Gids.add(gid);
        lvl1Map = coverageAreaIndex.firstTrackPointsAtDepth(originalTrack, lvl1Gids, deep3);
        List<SeedSel> freshAnchors = edgeRouter.parallelMap(touchedFixed, this::computeAnchorFor);
        countSourcesAndLog(freshAnchors);
        Map<Integer, SeedSel> freshByGid = new HashMap<>();
        for (SeedSel f : freshAnchors) {
            freshByGid.put(f.area().areaId(), f);
        }
        Set<Integer> placed = new HashSet<>();
        List<double[]> newRoute = new ArrayList<>(route.size());
        for (double[] p : route) {
            if (GeometryUtil.isAnchor(p, anchors)) {
                newRoute.add(p);
                continue;
            }
            UnvisitedArea g = coverageAreaIndex.findGminaForPoint(p[0], p[1]);
            if (g != null && freshByGid.containsKey(g.areaId()) && placed.add(g.areaId())) {
                newRoute.add(freshByGid.get(g.areaId()).point());
            }
        }
        route.clear();
        route.addAll(newRoute);
        for (SeedSel f : freshAnchors) {
            if (!placed.contains(f.area().areaId())) {
                route.add(GeometryUtil.cheapestInsertPos(route, f.point()), f.point());
            }
        }
        selected.removeIf(s -> !GeometryUtil.isAnchor(s.point(), anchors)
                && coverageAreaIndex.findGminaForPoint(s.point()[0], s.point()[1]) != null);
        selected.addAll(freshAnchors);
    }

    private SeedSel computeAnchorFor(int areaId) {
        if (deepAnchorAreaIds.contains(areaId)) return null;
        UnvisitedArea area = areaById.get(areaId);
        if (area == null) return null;
        int lvl = deepenLevel.getOrDefault(areaId, 0);
        double[] probed = sampleAnchor.get(areaId);
        double[] anchorPoint;
        if (probed != null) {
            anchorPoint = probed;
        } else {
            double[] entry = entryPointsFixed.get(areaId);
            double[] cel;
            if (lvl == 0) {
                cel = entry;
            } else if (lvl == 1) {
                cel = lvl1Map.get(areaId);
                if (cel == null && entry != null)
                    cel = GeometryUtil.movePointTowards(entry, deepInterior(area, areaId), 80.0);
            } else {
                double[] base = entry != null ? entry : deepInterior(area, areaId);
                cel = GeometryUtil.movePointTowards(base, deepInterior(area, areaId), lvl * 80.0);
            }
            anchorPoint = cel != null ? cel : deepInterior(area, areaId);
        }
        return new SeedSel(area, anchorPoint, ordering.orderKey(anchorPoint), 0.0,
                GeometryUtil.minDistToBaselineKm(anchorPoint, baseline));
    }

    private void countSourcesAndLog(List<SeedSel> freshAnchors) {
        fromSample = 0;
        lvl0Count = 0;
        deepenL1 = deepenL2 = deepenL3 = 0;
        Map<Integer, double[]> ptByGid = new HashMap<>();
        for (SeedSel s : freshAnchors) ptByGid.putIfAbsent(s.area().areaId(), s.point());
        for (int areaId : touchedFixed) {
            if (deepAnchorAreaIds.contains(areaId) || areaById.get(areaId) == null) continue;
            if (sampleAnchor.get(areaId) != null) { fromSample++; continue; }
            int lvl = deepenLevel.getOrDefault(areaId, 0);
            if (lvl == 0) { lvl0Count++; continue; }
            if (lvl == 1) deepenL1++; else if (lvl == 2) deepenL2++; else deepenL3++;
            double[] ap = ptByGid.get(areaId);
            if (debugGeoJson && lvl >= 2 && ap != null)
                log.info("Coverage ANCHOR-ESCALATE [{}] iter {}: {} lvl{} → cel-depth={}m (push {}m ku MIC)",
                        new Object[]{debugPhase, iteration, areaById.get(areaId).name(), lvl,
                                Math.round(coverageAreaIndex.depthMeters(ap, areaId)), lvl * 80});
        }
    }

    private void computeShallow(List<double[]> realTrack) {
        Set<Integer> deeply = coverageAreaIndex.deeplyVisitedAreaIds(realTrack);
        Set<Integer> shallow = new HashSet<>();
        for (int gid : touchedFixed)
            if (!deeply.contains(gid) && !deepAnchorAreaIds.contains(gid) && areaById.containsKey(gid))
                shallow.add(gid);
        shallowAreaIds = shallow;
        String names = "";
        if (!shallow.isEmpty() && shallow.size() < 50) {
            List<String> ns = new ArrayList<>();
            for (int gid : shallow) { UnvisitedArea a = areaById.get(gid); ns.add(a != null ? a.name() : "id" + gid); }
            ns.sort(null);
            names = " " + ns;
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, jezioro={}, swieze[220m={} 300m={} push160={} push240={}], slad<220m={}{}",
                new Object[]{debugPhase, iteration, touchedCount, fromSample, lvl0Count, deepenL1, deepenL2, deepenL3, shallow.size(), names});
    }

    private double[] deepInterior(UnvisitedArea area, int areaId) {
        double[] deepest = coverageAreaIndex.deepestInteriorPoint(areaId);
        return deepest != null ? deepest : new double[]{area.lng(), area.lat()};
    }

    private int probeSamples() {
        int probed = 0;
        for (int i = 1; i < route.size() - 1; i++) {
            double[] wp = route.get(i);
            UnvisitedArea area = coverageAreaIndex.findGminaForPoint(wp[0], wp[1]);
            if (area == null) continue;
            int gid = area.areaId();
            if (!shallowAreaIds.contains(gid)) continue;
            if (sampleAnchor.containsKey(gid)) continue;
            if (coverageAreaIndex.firstTrackPointAtDepth(originalTrack, gid, deepDepthM) != null) continue;
            double[] best = bestSampleEntry(area, gid, route.get(i - 1), route.get(i + 1));
            if (best != null) {
                sampleAnchor.put(gid, best);
                probed++;
            }
        }
        if (probed > 0) log.info("Coverage ANCHOR-INTERSECTS [{}]: próbowano-obwód → {} gmin dostało punkt obwodowy ≥220m",
                new Object[]{debugPhase, probed});
        return probed;
    }

    private double[] bestSampleEntry(UnvisitedArea area, int gid, double[] prev, double[] next) {
        double[][] samples = coverageAreaIndex.samplePointsFor(area);
        double[] best250 = null, best220 = null, fallbackReachable = null;
        for (double[] s : samples) {
            List<double[]> in = edgeRouter.edge(prev, s).geometry();
            List<double[]> out = edgeRouter.edge(s, next).geometry();
            if (edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(prev, s))
                    || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(s, next))) continue;
            List<double[]> seg = new ArrayList<>(in.size() + out.size());
            seg.addAll(in);
            seg.addAll(out);
            double[] d300 = coverageAreaIndex.firstTrackPointAtDepth(seg, gid, deep3);
            if (d300 != null) return d300;
            if (best250 == null) best250 = coverageAreaIndex.firstTrackPointAtDepth(seg, gid, deep2);
            if (best250 == null && best220 == null) best220 = coverageAreaIndex.firstTrackPointAtDepth(seg, gid, deepDepthM);
            if (fallbackReachable == null && !in.isEmpty()) {
                double[] snap = in.get(in.size() - 1);
                if (velomarker.service.planning.WaypointSelector.haversineKm(s, snap) <= REACHABLE_KM) fallbackReachable = s;
            }
        }
        return best250 != null ? best250 : best220 != null ? best220 : fallbackReachable;
    }

    private boolean escalateShallow() {
        boolean progress = false;
        for (int gid : shallowAreaIds) {
            int lvl = deepenLevel.getOrDefault(gid, 0);
            if (lvl < 3) {
                deepenLevel.put(gid, lvl + 1);
                progress = true;
            }
        }
        return progress;
    }
}
