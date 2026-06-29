package velomarker.service;

/** Geodezyjne odległości (great-circle, haversine). Punkty jako [lng, lat]. */
public final class GeoMath {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoMath() {}

    public static double haversineKm(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[1]), lat2 = Math.toRadians(b[1]);
        double dLat = lat2 - lat1, dLng = Math.toRadians(b[0] - a[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    public static double haversineM(double[] a, double[] b) {
        return haversineKm(a, b) * 1000.0;
    }
}
