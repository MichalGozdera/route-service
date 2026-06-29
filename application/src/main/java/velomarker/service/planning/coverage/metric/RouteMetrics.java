package velomarker.service.planning.coverage.metric;

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

import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Miary trasy: effort, dystans, wznios, sklejona geometria, zaliczone gminy. Instancja per plan.
public final class RouteMetrics {

    private static final int CLIMB_WINDOW_PTS = 400;

    private final EdgeRouter edges;
    private final CoverageAreaIndex coverageAreaIndex;
    private final ElevationDataSource elevation;
    private final double alpha;

    public RouteMetrics(EdgeRouter edges, CoverageAreaIndex coverageAreaIndex, ElevationDataSource elevation, double alpha) {
        this.edges = edges;
        this.coverageAreaIndex = coverageAreaIndex;
        this.elevation = elevation;
        this.alpha = alpha;
    }

    public double effortViaCache(List<double[]> route) {
        edges.prewarm(route);
        double e = 0;
        for (int i = 0; i < route.size() - 1; i++) e += edges.edge(route.get(i), route.get(i + 1)).effort();
        return e;
    }

    public double effortAccurate(List<double[]> route) {
        return realKm(route) + alpha * climbM(realGeometry(route));
    }

    public double realKm(List<double[]> route) {
        double km = 0;
        for (int i = 0; i < route.size() - 1; i++) km += edges.edge(route.get(i), route.get(i + 1)).distanceKm();
        return km;
    }

    public double haversineKm(List<double[]> route) {
        double hav = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            hav += velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
        }
        return hav;
    }

    public double climbM(List<double[]> coords) {
        if (elevation == null || coords == null || coords.size() < 2) return 0;
        double total = 0;
        for (int i = 0; i < coords.size() - 1; i += CLIMB_WINDOW_PTS) {
            int end = Math.min(coords.size(), i + CLIMB_WINDOW_PTS + 1);
            try {
                List<double[]> window = coords.subList(i, end);
                total += elevation.sample(window, window.size()).gainM();
            } catch (RuntimeException ignored) { }
        }
        return total;
    }

    public List<double[]> realGeometry(List<double[]> route) {
        List<double[]> geom = new ArrayList<>();
        for (int i = 0; i < route.size() - 1; i++) {
            EdgeInfo info = edges.edge(route.get(i), route.get(i + 1));
            List<double[]> seg = info.geometry();
            if (seg == null || seg.isEmpty()) continue;
            int from = geom.isEmpty() ? 0 : 1;
            for (int j = from; j < seg.size(); j++) geom.add(seg.get(j));
        }
        return geom;
    }

    public EvalResult eval(List<double[]> route) {
        edges.prewarm(route);
        List<double[]> geom = new ArrayList<>();
        double effort = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            EdgeInfo info = edges.edge(route.get(i), route.get(i + 1));
            effort += info.effort();
            List<double[]> eg = info.geometry();
            int from = geom.isEmpty() ? 0 : 1;
            for (int k = from; k < eg.size(); k++) geom.add(eg.get(k));
        }
        return new EvalResult(effort, coverageAreaIndex.visitedAreaIds(geom), geom);
    }
}
