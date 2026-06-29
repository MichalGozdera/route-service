package velomarker.service.planning.coverage.index;

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
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.AreaPassage;
import velomarker.port.out.planning.SpatialIndex;
import velomarker.port.out.planning.SpatialIndexFactory;
import velomarker.service.planning.WaypointSelector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Pomocnik planowania nad pulą nieodwiedzonych obszarów: pokrycie + heurystyki seeda.
public class CoverageAreaIndex {

    private static final int HOLE_KNN = 8;

    private final List<UnvisitedArea> allAreas;
    private final AreaCoverageIndex coverage;
    private final SpatialIndexFactory spatialIndexFactory;
    private final Set<Integer> historicallyVisited;
    private final Map<Integer, double[][]> samplePointsCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SAMPLE_POINTS = 8;

    private static final double SAMPLE_OFFSET_DEG = 0.0045;
    private Map<Integer, int[]> kNearestCache;

    public CoverageAreaIndex(List<UnvisitedArea> areas, AreaCoverageIndex coverage, SpatialIndexFactory spatialIndexFactory) {
        this(areas, coverage, spatialIndexFactory, java.util.Set.of());
    }

    public CoverageAreaIndex(List<UnvisitedArea> areas, AreaCoverageIndex coverage, SpatialIndexFactory spatialIndexFactory,
                      Set<Integer> historicallyVisited) {
        this.allAreas = new ArrayList<>(areas);
        this.coverage = coverage;
        this.spatialIndexFactory = spatialIndexFactory;
        this.historicallyVisited = historicallyVisited == null ? java.util.Set.of() : Set.copyOf(historicallyVisited);
    }

    public Set<Integer> historicallyVisited() {
        return historicallyVisited;
    }

    public Set<Integer> visitedUnion(List<double[]> routeGeometry) {
        Set<Integer> u = new java.util.HashSet<>(historicallyVisited);
        u.addAll(coverage.visitedAreaIds(routeGeometry));
        return u;
    }

    public static final double HOLE_BORDER_FRACTION = 0.9;

    public double neighborVisitedFraction(int areaId, Set<Integer> visited) {
        return coverage.neighborVisitedFraction(areaId, visited);
    }

    public boolean enclosedByVisited(int areaId, Set<Integer> visited) {
        return neighborVisitedFraction(areaId, visited) >= HOLE_BORDER_FRACTION;
    }

    public Map<Integer, Integer> enclosedHoleSizes(Set<Integer> visited) {
        return coverage.enclosedRegionSizes(visited, HOLE_BORDER_FRACTION);
    }

    public UnvisitedArea findGminaForPoint(double lng, double lat) {
        return coverage.findAreaForPoint(lng, lat);
    }

    public UnvisitedArea findCreditedGminaForPoint(double lng, double lat) {
        return coverage.findCreditedAreaForPoint(lng, lat);
    }

    public UnvisitedArea findDeeplyCreditedGminaForPoint(double lng, double lat) {
        return coverage.findDeeplyCreditedAreaForPoint(lng, lat);
    }

    public Set<Integer> visitedAreaIds(List<double[]> routeGeometry) {
        return coverage.visitedAreaIds(routeGeometry);
    }

    public Set<Integer> deeplyVisitedAreaIds(List<double[]> routeGeometry) {
        return coverage.deeplyVisitedAreaIds(routeGeometry);
    }

    public Set<Integer> touchedAreaIds(List<double[]> routeGeometry) {
        return coverage.touchedAreaIds(routeGeometry);
    }

    public Map<Integer, double[]> firstBufferEntryPoints(List<double[]> routeGeometry) {
        return coverage.firstBufferEntryPoints(routeGeometry);
    }

    public Map<Integer, List<AreaPassage>> passages(List<double[]> routeGeometry) {
        return coverage.passages(routeGeometry);
    }

    public double[] deepestInteriorPoint(int areaId) {
        return coverage.deepestInteriorPoint(areaId);
    }

    public double[] firstTrackPointAtDepth(List<double[]> track, int areaId, double minDepthMeters) {
        return coverage.firstTrackPointAtDepth(track, areaId, minDepthMeters);
    }

    public double depthMeters(double[] point, int areaId) {
        return coverage.depthMeters(point, areaId);
    }

    public Map<Integer, double[]> deepestPointsOnTrack(List<double[]> track, Set<Integer> areaIds) {
        return coverage.deepestPointsOnTrack(track, areaIds);
    }

    public Map<Integer, double[]> firstTrackPointsAtDepth(List<double[]> track, Set<Integer> areaIds, double minDepthMeters) {
        return coverage.firstTrackPointsAtDepth(track, areaIds, minDepthMeters);
    }

    public Set<Integer> enclosedUnvisited(Set<Integer> visited) {
        return coverage.enclosedUnvisited(visited);
    }

    public boolean allNeighborsVisited(int areaId, Set<Integer> visited) {
        return coverage.allNeighborsVisited(areaId, visited);
    }

    public Set<Integer> borderAreaIds(Set<Integer> visited) {
        return coverage.borderAreaIds(visited);
    }

    public double[][] samplePointsFor(UnvisitedArea area) {
        return samplePointsCache.computeIfAbsent(area.areaId(), id -> computeSamples(area, SAMPLE_OFFSET_DEG));
    }

    private double[][] computeSamples(UnvisitedArea area, double offsetDeg) {
        double[][] ring = area.ring();
        if (ring == null || ring.length == 0) {
            return new double[][]{{area.lng(), area.lat()}};
        }
        int n = Math.min(SAMPLE_POINTS, ring.length);
        double[][] pts = new double[n][];
        int step = Math.max(1, ring.length / n);
        double cLng = area.lng();
        double cLat = area.lat();
        for (int i = 0; i < n; i++) {
            int idx = Math.min(ring.length - 1, i * step);
            double vLng = ring[idx][0];
            double vLat = ring[idx][1];
            double dirLng = cLng - vLng;
            double dirLat = cLat - vLat;
            double len = Math.sqrt(dirLng * dirLng + dirLat * dirLat);
            if (len < 1e-9) {
                pts[i] = new double[]{vLng, vLat};
            } else {
                double scale = Math.min(offsetDeg / len, 0.5);
                pts[i] = new double[]{vLng + dirLng * scale, vLat + dirLat * scale};
            }
        }
        return pts;
    }

    public double distToRoute(UnvisitedArea area, List<double[]> route) {
        double[] p = deepestInteriorPoint(area.areaId());
        if (p == null) p = new double[]{area.lng(), area.lat()};
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < route.size() - 1; i++) {
            double[] a = route.get(i);
            double[] b = route.get(i + 1);
            double d = pointToSegmentKm(p[0], p[1], a[0], a[1], b[0], b[1]);
            if (d < minDist) minDist = d;
            if (minDist < 0.5) return minDist;
        }
        return minDist;
    }

    private static double pointToSegmentKm(double px, double py,
                                            double ax, double ay,
                                            double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t;
        if (len2 < 1e-12) t = 0;
        else {
            t = ((px - ax) * dx + (py - ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
        }
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        return WaypointSelector.haversineKm(new double[]{px, py}, new double[]{projX, projY});
    }

    public double enclosedFraction(int areaId, Set<Integer> visited) {
        int[] nn = kNearest().get(areaId);
        if (nn == null || nn.length == 0) return 0;
        int hit = 0;
        for (int id : nn) {
            if (visited.contains(id)) hit++;
        }
        return (double) hit / nn.length;
    }

    private Map<Integer, int[]> kNearest() {
        if (kNearestCache != null) return kNearestCache;
        int n = allAreas.size();
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) pts[i] = new double[]{allAreas.get(i).lng(), allAreas.get(i).lat()};
        SpatialIndex grid = spatialIndexFactory.build(pts);
        int k = Math.min(HOLE_KNN, Math.max(0, n - 1));
        Map<Integer, int[]> result = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            int[] nnIdx = grid.kNearestIndices(i, k);
            int[] ids = new int[nnIdx.length];
            for (int t = 0; t < nnIdx.length; t++) ids[t] = allAreas.get(nnIdx[t]).areaId();
            result.put(allAreas.get(i).areaId(), ids);
        }
        kNearestCache = result;
        return result;
    }

    public static double avgNearestNeighborDistKm(List<UnvisitedArea> categoryAreas, SpatialIndexFactory factory) {
        int n = categoryAreas.size();
        if (n < 2) return 0;
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) {
            UnvisitedArea a = categoryAreas.get(i);
            pts[i] = new double[]{a.lng(), a.lat()};
        }
        SpatialIndex grid = factory.build(pts);
        double sumNN = 0;
        for (int i = 0; i < n; i++) {
            double d = grid.nearestDistKm(i);
            if (d < Double.MAX_VALUE) sumNN += d;
        }
        return sumNN / n;
    }
}
