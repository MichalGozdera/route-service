package velomarker.entity.planning;

/**
 * Punkt na trasie. {@code name} opcjonalna — reverse-geocode robi frontend i przekazuje
 * etykietę z formularza (start/meta/via). Backend traktuje name jako display-only.
 */
public record Waypoint(double lng, double lat, String name) {

    public double[] toLngLat() {
        return new double[]{lng, lat};
    }
}
