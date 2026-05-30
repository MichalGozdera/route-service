package velomarker.entity;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copernicus DEM GLO-30 tile name, 1°×1° aligned to integer lat/lon.
 * Display form: "N50E020", "S05W120", etc. — matches Open Topo Data's
 * {@code filename_tile_size: 1} expectation when stored as "{name}.tif".
 */
public record DemTileName(String name, int lonStart, int latStart) {

    private static final Pattern PATTERN = Pattern.compile("^([NS])(\\d{2})([EW])(\\d{3})$");

    public DemTileName {
        Objects.requireNonNull(name, "name");
        if (latStart < -90 || latStart > 89 || lonStart < -180 || lonStart > 179) {
            throw new IllegalArgumentException("Out-of-range lat/lon: " + name);
        }
    }

    public static DemTileName parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String s = (raw.endsWith(".tif") || raw.endsWith(".hgt")) ? raw.substring(0, raw.length() - 4) : raw;
        Matcher m = PATTERN.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a valid DEM tile name: " + raw);
        }
        int lat = Integer.parseInt(m.group(2));
        if ("S".equals(m.group(1))) lat = -lat;
        int lon = Integer.parseInt(m.group(4));
        if ("W".equals(m.group(3))) lon = -lon;
        return new DemTileName(s, lon, lat);
    }

    public static DemTileName of(int lonStart, int latStart) {
        String lat = (latStart >= 0 ? "N" : "S") + String.format("%02d", Math.abs(latStart));
        String lon = (lonStart >= 0 ? "E" : "W") + String.format("%03d", Math.abs(lonStart));
        return new DemTileName(lat + lon, lonStart, latStart);
    }

    public String fileName() { return name + ".tif"; }

    /** Nazwa pliku po konwersji na SRTM HGT (to, co realnie czyta route-service). */
    public String hgtFileName() { return name + ".hgt"; }

    /** Rough Europe bounding box: lon -15..45, lat 30..72 (matches BRouter SegmentName Europe filter). */
    public boolean isInEurope() {
        return lonStart >= -15 && lonStart <= 45 && latStart >= 30 && latStart <= 72;
    }
}
