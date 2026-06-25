package velomarker.service.planning.coverage;

import velomarker.service.planning.WaypointSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * Czyste funkcje geometryczne planera pokrycia (haversine, dystanse punkt→segment, projekcje,
 * sub/downsampling, wyszukiwanie wierzchołków). Bezstanowe — wszystkie metody static.
 */
final class GeometryUtil {

    private GeometryUtil() {}

    /** Haversine (km) między dwoma punktami [lng,lat]. */
    static double hav(double[] a, double[] b) {
        return WaypointSelector.haversineKm(a, b);
    }

    /** Przesuń punkt {@code from} o {@code meters} w kierunku {@code to} (interpolacja liniowa lon/lat — wystarcza
     *  dla małych odległości ~80-240m). Gdy {@code meters} ≥ dystans from→to (lub dystans ≈ 0) → zwraca {@code to}. */
    static double[] movePointTowards(double[] from, double[] to, double meters) {
        double distM = WaypointSelector.haversineKm(from, to) * 1000.0;
        if (distM <= meters || distM < 1e-6) return to.clone();
        double f = meters / distM;
        return new double[]{from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f};
    }

    /** EKSTRAPOLACJA: punkt ZA {@code to} o {@code meters} wzdłuż kierunku from→to (przedłuża odcinek). W odróżnieniu
     *  od {@link #movePointTowards} (cap na {@code to}) idzie DALEJ. Gdy dystans from→to ≈ 0 → {@code to}. */
    static double[] extendBeyond(double[] from, double[] to, double meters) {
        double distM = WaypointSelector.haversineKm(from, to) * 1000.0;
        if (distM < 1e-6) return to.clone();
        double f = (distM + meters) / distM;
        return new double[]{from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f};
    }

    /** Łączna długość (km) polilinii (suma haversine kolejnych par). */
    static double polyHavKm(List<double[]> geom) {
        double sum = 0;
        for (int i = 1; i < geom.size(); i++) {
            sum += WaypointSelector.haversineKm(geom.get(i - 1), geom.get(i));
        }
        return sum;
    }

    /**
     * Analityczna odległość punkt→odcinek (km) — rzut prostopadły w płaszczyźnie equirectangular
     * (lng × cos(lat)), clamp t∈[0,1], jeden sqrt, zero trygonometrii w pętli. Dla segmentu
     * zawierającego punkt zwraca ≈0 (early-break w skanach).
     */
    static double pointToSegmentExactKm(double[] p, double[] a, double[] b) {
        double latRad = Math.toRadians((a[1] + b[1]) / 2.0);
        double kx = 111.320 * Math.cos(latRad), ky = 110.574;
        double ax = a[0] * kx, ay = a[1] * ky, bx = b[0] * kx, by = b[1] * ky, px = p[0] * kx, py = p[1] * ky;
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 <= 1e-12 ? 0.0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = px - (ax + dx * t), ey = py - (ay + dy * t);
        return Math.sqrt(ex * ex + ey * ey);
    }

    /** Min haversine (km) od punktu do downsampled baseline polyline. */
    static double minDistToBaselineKm(double[] p, List<double[]> baseline) {
        if (baseline == null || baseline.isEmpty()) return 0;
        double min = Double.MAX_VALUE;
        for (double[] b : baseline) {
            double d = WaypointSelector.haversineKm(p, b);
            if (d < min) min = d;
        }
        return min;
    }

    /** Równomierny downsample do {@code target} punktów (zawsze zawiera ostatni). */
    static List<double[]> downsample(List<double[]> coords, int target) {
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

    /** Równomierny subsample gęstej geometrii do ~n punktów (zachowuje pierwszy i ostatni). */
    static List<double[]> subsampleGeometry(List<double[]> geom, int n) {
        if (geom == null || geom.size() <= n) return geom;
        int step = Math.max(1, geom.size() / n);
        List<double[]> out = new ArrayList<>(n + 1);
        for (int i = 0; i < geom.size(); i += step) out.add(geom.get(i));
        double[] last = geom.get(geom.size() - 1);
        if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        return out;
    }


    /** Pozycja najtańszej insercji punktu w trasę (haversine; in-memory). */
    static int cheapestInsertPos(List<double[]> route, double[] p) {
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

    /** Indeks punktu w route po TOŻSAMOŚCI (==), odporny na przesunięcia po delete/insert. -1 gdy brak. */
    static int identityIndexOf(List<double[]> route, double[] p) {
        for (int i = 0; i < route.size(); i++) if (route.get(i) == p) return i;
        return -1;
    }

    /** Stabilny klucz krawędzi A→B (6 miejsc po przecinku). */
    static String edgeKey(double[] a, double[] b) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", a[0], a[1], b[0], b[1]);
    }

    /** Czy punkt p pokrywa się (≈10cm) z którymś z anchorów (start/via/end). */
    static boolean isAnchor(double[] p, List<double[]> anchors) {
        for (double[] a : anchors) {
            if (Math.abs(a[0] - p[0]) < 1e-6 && Math.abs(a[1] - p[1]) < 1e-6) return true;
        }
        return false;
    }
}
