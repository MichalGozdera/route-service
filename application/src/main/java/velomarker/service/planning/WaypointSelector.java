package velomarker.service.planning;

import java.util.List;

/**
 * Helpery geometryczne używane w planowaniu trasy: test punkt-w-obrysie (ray casting), suma odcinków
 * po linii prostej i haversine. Czysty kod deterministyczny; wszystkie punkty to pary [lng, lat].
 *
 * <p>Bean Springa — {@link #straightLineDistanceKm} jest instancyjne (woła je BaselineComputer);
 * {@link #pointInRing} i {@link #haversineKm} są statyczne i używane szeroko w pakiecie coverage.
 */
public class WaypointSelector {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Ray casting: czy punkt [lng,lat] leży wewnątrz obrysu (ring punktów [lng,lat]). */
    public static boolean pointInRing(double[] p, double[][] ring) {
        boolean inside = false;
        double x = p[0];
        double y = p[1];
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0];
            double yi = ring[i][1];
            double xj = ring[j][0];
            double yj = ring[j][1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Suma odcinków po linii prostej (km) — np. do szacowania długości przed brouter. */
    public double straightLineDistanceKm(List<double[]> waypoints) {
        double sum = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            sum += haversineKm(waypoints.get(i - 1), waypoints.get(i));
        }
        return sum;
    }

    public static double haversineKm(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b[0] - a[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
