package velomarker.service.planning.coverage.geom;

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

import velomarker.service.planning.WaypointSelector;

import java.util.ArrayList;
import java.util.List;

// Czyste funkcje geometryczne planera pokrycia.
public final class GeometryUtil {

    private GeometryUtil() {}

    public static double hav(double[] a, double[] b) {
        return WaypointSelector.haversineKm(a, b);
    }

    public static double[] closestRingPoint(double[][] ring, double[] p) {
        double[] best = ring[0];
        double bestD = Double.MAX_VALUE;
        for (double[] r : ring) {
            double d = WaypointSelector.haversineKm(r, p);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    public static double[] movePointTowards(double[] from, double[] to, double meters) {
        double distM = WaypointSelector.haversineKm(from, to) * 1000.0;
        if (distM <= meters || distM < 1e-6) return to.clone();
        double f = meters / distM;
        return new double[]{from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f};
    }

    public static double[] extendBeyond(double[] from, double[] to, double meters) {
        double distM = WaypointSelector.haversineKm(from, to) * 1000.0;
        if (distM < 1e-6) return to.clone();
        double f = (distM + meters) / distM;
        return new double[]{from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f};
    }

    public static double polyHavKm(List<double[]> geom) {
        double sum = 0;
        for (int i = 1; i < geom.size(); i++) {
            sum += WaypointSelector.haversineKm(geom.get(i - 1), geom.get(i));
        }
        return sum;
    }

    public static double pointToSegmentExactKm(double[] p, double[] a, double[] b) {
        double latRad = Math.toRadians((a[1] + b[1]) / 2.0);
        double kx = 111.320 * Math.cos(latRad), ky = 110.574;
        double ax = a[0] * kx, ay = a[1] * ky, bx = b[0] * kx, by = b[1] * ky, px = p[0] * kx, py = p[1] * ky;
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 <= 1e-12 ? 0.0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = px - (ax + dx * t), ey = py - (ay + dy * t);
        return Math.sqrt(ex * ex + ey * ey);
    }

    public static double minDistToBaselineKm(double[] p, List<double[]> baseline) {
        if (baseline == null || baseline.isEmpty()) return 0;
        double min = Double.MAX_VALUE;
        for (double[] b : baseline) {
            double d = WaypointSelector.haversineKm(p, b);
            if (d < min) min = d;
        }
        return min;
    }

    public static List<double[]> downsample(List<double[]> coords, int target) {
        if (coords == null || coords.size() <= target) return new ArrayList<>(coords);
        List<double[]> result = new ArrayList<>(target);
        double step = (double) coords.size() / target;
        for (int i = 0; i < target; i++) {
            int idx = Math.min(coords.size() - 1, (int) Math.round(i * step));
            result.add(coords.get(idx));
        }
        if (!result.isEmpty() && result.get(result.size() - 1) != coords.get(coords.size() - 1)) {
            result.set(result.size() - 1, coords.get(coords.size() - 1));
        }
        return result;
    }

    public static List<double[]> subsampleGeometry(List<double[]> geom, int n) {
        if (geom == null || geom.size() <= n) return geom;
        int step = Math.max(1, geom.size() / n);
        List<double[]> out = new ArrayList<>(n + 1);
        for (int i = 0; i < geom.size(); i += step) out.add(geom.get(i));
        double[] last = geom.get(geom.size() - 1);
        if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        return out;
    }


    public static int cheapestInsertPos(List<double[]> route, double[] p) {
        int bestPos = 1;
        double best = Double.MAX_VALUE;
        for (int i = 1; i < route.size(); i++) {
            double cost = WaypointSelector.haversineKm(route.get(i - 1), p)
                    + WaypointSelector.haversineKm(p, route.get(i))
                    - WaypointSelector.haversineKm(route.get(i - 1), route.get(i));
            if (cost < best) { best = cost; bestPos = i; }
        }
        return bestPos;
    }

    public static int identityIndexOf(List<double[]> route, double[] p) {
        for (int i = 0; i < route.size(); i++) if (route.get(i) == p) return i;
        return -1;
    }

    public static String edgeKey(double[] a, double[] b) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
    }

    public static boolean isAnchor(double[] p, List<double[]> anchors) {
        for (double[] a : anchors) {
            if (Math.abs(a[0] - p[0]) < 1e-6 && Math.abs(a[1] - p[1]) < 1e-6) return true;
        }
        return false;
    }
}
