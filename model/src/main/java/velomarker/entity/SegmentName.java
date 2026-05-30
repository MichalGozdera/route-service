package velomarker.entity;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BRouter segment tile name, e.g. "E20_N50", "W5_N50", "E10_S5".
 * Tiles are 5°×5° aligned to integer multiples of 5.
 * lonStart = west longitude of tile (signed, -180..175).
 * latStart = south latitude of tile (signed, -90..85).
 */
public record SegmentName(String name, int lonStart, int latStart) {

    private static final Pattern PATTERN = Pattern.compile("^([EW])(\\d+)_([NS])(\\d+)$");

    public SegmentName {
        Objects.requireNonNull(name, "name");
        if (lonStart % 5 != 0 || latStart % 5 != 0) {
            throw new IllegalArgumentException("lon/lat must be multiples of 5: " + name);
        }
    }

    public static SegmentName parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String s = raw.endsWith(".rd5") ? raw.substring(0, raw.length() - 4) : raw;
        Matcher m = PATTERN.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a valid BRouter segment name: " + raw);
        }
        int lon = Integer.parseInt(m.group(2));
        if ("W".equals(m.group(1))) lon = -lon;
        int lat = Integer.parseInt(m.group(4));
        if ("S".equals(m.group(3))) lat = -lat;
        return new SegmentName(s, lon, lat);
    }

    public String fileName() { return name + ".rd5"; }

    /** Rough Europe bounding box: lon -15..45, lat 30..72. */
    public boolean isInEurope() {
        return lonStart >= -15 && lonStart <= 45 && latStart >= 30 && latStart <= 72;
    }
}
