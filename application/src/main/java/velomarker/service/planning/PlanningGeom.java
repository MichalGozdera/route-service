package velomarker.service.planning;

import java.util.List;

/** Czyste helpery geometryczne dla wyboru gmin: przecięcia segment/ring, bbox polilinii, najbliższy punkt/indeks. */
final class PlanningGeom {

    private PlanningGeom() {}
    /** Najbliższy wierzchołek ringa do punktu p. */
    static double[] closestRingPoint(double[][] ring, double[] p) {
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

    /**
     * Bbox polyline z OSOBNYMI marginesami lng/lat (w stopniach). [minLng, minLat, maxLng, maxLat].
     * Osobne marginesy konieczne bo 1° lng ≠ 1° lat (lng kurczy się ku biegunom przez cos(lat)).
     */
    static double[] polylineBbox(List<double[]> poly, double marginLngDeg, double marginLatDeg) {
        double minLng = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (double[] p : poly) {
            if (p[0] < minLng) minLng = p[0];
            if (p[0] > maxLng) maxLng = p[0];
            if (p[1] < minLat) minLat = p[1];
            if (p[1] > maxLat) maxLat = p[1];
        }
        return new double[]{minLng - marginLngDeg, minLat - marginLatDeg,
                            maxLng + marginLngDeg, maxLat + marginLatDeg};
    }

    /** Indeks punktu w polyline najbliższy zadanej pozycji (lng, lat). */
    static int findNearestGeomIdx(List<double[]> geom, double[] target) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < geom.size(); i++) {
            double d = WaypointSelector.haversineKm(geom.get(i), target);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Czy odcinek p1→p2 przecina poligon (ring)? Test: czy któryś sąsiedni ring vertex jest po przeciwnej stronie. */
    static boolean segmentIntersectsRing(double[] p1, double[] p2, double[][] ring) {
        // Najpierw szybkie odrzucenie po bbox.
        double minLng = Math.min(p1[0], p2[0]), maxLng = Math.max(p1[0], p2[0]);
        double minLat = Math.min(p1[1], p2[1]), maxLat = Math.max(p1[1], p2[1]);
        double ringMinLng = Double.MAX_VALUE, ringMaxLng = -Double.MAX_VALUE;
        double ringMinLat = Double.MAX_VALUE, ringMaxLat = -Double.MAX_VALUE;
        for (double[] r : ring) {
            if (r[0] < ringMinLng) ringMinLng = r[0];
            if (r[0] > ringMaxLng) ringMaxLng = r[0];
            if (r[1] < ringMinLat) ringMinLat = r[1];
            if (r[1] > ringMaxLat) ringMaxLat = r[1];
        }
        if (maxLng < ringMinLng || minLng > ringMaxLng || maxLat < ringMinLat || minLat > ringMaxLat) return false;
        // Endpoint test — szybko jeśli koniec w ringu.
        if (WaypointSelector.pointInRing(p1, ring) || WaypointSelector.pointInRing(p2, ring)) return true;
        // Test przecięcia z każdym bokiem ringa.
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            if (segmentsIntersect(p1, p2, ring[j], ring[i])) return true;
        }
        return false;
    }

    /** Standardowy test przecięcia dwóch odcinków a-b i c-d (2D). */
    static boolean segmentsIntersect(double[] a, double[] b, double[] c, double[] d) {
        double d1 = cross(c, d, a);
        double d2 = cross(c, d, b);
        double d3 = cross(a, b, c);
        double d4 = cross(a, b, d);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        return false;
    }

    /** Haversine od punktu do odcinka start→end (przybliżenie planarne; ok dla wybierania kandydatów). */
    static double distanceToSegmentKm(double[] p, double[] a, double[] b) {
        double ax = a[0], ay = a[1];
        double bx = b[0], by = b[1];
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0] - ax) * dx + (p[1] - ay) * dy) / len2));
        double[] proj = new double[]{ax + t * dx, ay + t * dy};
        return WaypointSelector.haversineKm(p, proj);
    }

    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }
}
