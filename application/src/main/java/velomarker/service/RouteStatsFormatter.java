package velomarker.service;

import velomarker.entity.RouteStats;

import java.util.HashMap;
import java.util.Map;

/**
 * Formatuje {@link RouteStats} jako wieloliniowy "ludzki" string PL do logów. Tłumaczy
 * znormalizowane kody (z {@code RouteStatsBuilder}) na PL etykiety; to backend's view layer.
 *
 * <p>Frontend ma WŁASNE mapy translacji per locale (i18n) — ten formatter nie jest częścią
 * API contractu, tylko view dla backendowego logu.
 */
public final class RouteStatsFormatter {

    private RouteStatsFormatter() {
    }

    private static final Map<String, String> SURFACE_PL = new HashMap<>();
    private static final Map<String, String> ROAD_PL = new HashMap<>();
    private static final Map<String, String> SMOOTHNESS_PL = new HashMap<>();

    static {
        // surface — raw OSM tags → PL
        SURFACE_PL.put("asphalt", "asfalt");
        SURFACE_PL.put("paved", "asfalt");
        SURFACE_PL.put("chipseal", "asfalt");
        SURFACE_PL.put("paving_stones", "kostka brukowa");
        SURFACE_PL.put("sett", "kostka brukowa");
        SURFACE_PL.put("cobblestone", "kostka brukowa");
        SURFACE_PL.put("unhewn_cobblestone", "kostka brukowa");
        SURFACE_PL.put("bricks", "kostka brukowa");
        SURFACE_PL.put("concrete", "beton");
        SURFACE_PL.put("concrete:plates", "beton");
        SURFACE_PL.put("concrete:lanes", "beton");
        SURFACE_PL.put("gravel", "szuter");
        SURFACE_PL.put("fine_gravel", "drobny szuter");
        SURFACE_PL.put("pebblestone", "szuter");
        SURFACE_PL.put("compacted", "utwardzona");
        SURFACE_PL.put("unpaved", "nieutwardzona");
        SURFACE_PL.put("ground", "grunt");
        SURFACE_PL.put("dirt", "grunt");
        SURFACE_PL.put("earth", "grunt");
        SURFACE_PL.put("mud", "błoto");
        SURFACE_PL.put("sand", "piasek");
        SURFACE_PL.put("grass", "trawa");
        SURFACE_PL.put("grass_paver", "trawa");
        SURFACE_PL.put("wood", "deski");
        SURFACE_PL.put("metal", "metal");
        SURFACE_PL.put("rock", "skała");
        SURFACE_PL.put("unknown", "brak danych");

        // road — znormalizowane kody → PL
        ROAD_PL.put("motorway", "autostrada");
        ROAD_PL.put("trunk", "droga ekspresowa");
        ROAD_PL.put("primary", "droga główna 1. klasy");
        ROAD_PL.put("secondary", "droga regionalna");
        ROAD_PL.put("tertiary", "droga lokalna główna");
        ROAD_PL.put("unclassified", "droga lokalna");
        ROAD_PL.put("residential", "droga osiedlowa");
        ROAD_PL.put("living_street", "strefa zamieszkania");
        ROAD_PL.put("service", "droga dojazdowa");
        ROAD_PL.put("cycleway", "ścieżka rowerowa");
        ROAD_PL.put("cycleway_shared_foot", "ścieżka pieszo-rowerowa");
        ROAD_PL.put("path_bike_foot", "ścieżka pieszo-rowerowa");
        ROAD_PL.put("path_bike", "ścieżka rowerowa");
        ROAD_PL.put("path_foot", "ścieżka piesza");
        ROAD_PL.put("path", "ścieżka");
        ROAD_PL.put("footway", "chodnik");
        ROAD_PL.put("footway_bike_allowed", "chodnik (rower dozwolony)");
        ROAD_PL.put("pedestrian", "deptak");
        ROAD_PL.put("pedestrian_bike_allowed", "deptak (rower dozwolony)");
        ROAD_PL.put("track", "droga polna");
        ROAD_PL.put("bridleway", "ścieżka konna");
        ROAD_PL.put("steps", "schody");
        ROAD_PL.put("unknown", "brak danych");

        // smoothness — raw OSM tags → PL
        SMOOTHNESS_PL.put("excellent", "doskonała");
        SMOOTHNESS_PL.put("good", "dobra");
        SMOOTHNESS_PL.put("intermediate", "średnia");
        SMOOTHNESS_PL.put("bad", "zła");
        SMOOTHNESS_PL.put("very_bad", "bardzo zła");
        SMOOTHNESS_PL.put("horrible", "fatalna");
        SMOOTHNESS_PL.put("very_horrible", "katastrofalna");
        SMOOTHNESS_PL.put("impassable", "nieprzejezdna");
        SMOOTHNESS_PL.put("unknown", "brak danych");
    }

    public static String format(RouteStats stats, String title) {
        if (stats == null || stats.totalMeters() == 0) {
            return "";
        }
        long total = stats.totalMeters();
        StringBuilder sb = new StringBuilder("\n");
        sb.append("========== ").append(title).append(" ==========\n");
        sb.append(String.format("długość : %.2f km%n%n", total / 1000.0));

        sb.append("Typy nawierzchni:\n");
        appendMap(sb, stats.surfaceMeters(), total, RouteStatsFormatter::translateSurface);
        sb.append('\n');

        sb.append("Typy dróg:\n");
        appendMap(sb, stats.roadMeters(), total, RouteStatsFormatter::translateRoad);
        sb.append('\n');

        sb.append("Jakość nawierzchni:\n");
        appendMap(sb, stats.smoothnessMeters(), total, RouteStatsFormatter::translateSmoothness);

        sb.append("======================================");
        return sb.toString();
    }

    /** Tłumaczy znormalizowany surface code na PL. {@code "unknown"} → "brak danych". */
    public static String translateSurface(String code) {
        return SURFACE_PL.getOrDefault(code, "inne (" + code + ")");
    }

    /**
     * Tłumaczy znormalizowany road code na PL etykietę. Obsługuje suffixy:
     * <ul>
     *   <li>{@code _with_cycleway_lane} → " z pasem rowerowym"</li>
     *   <li>{@code _use_sidepath} → " (obowiązek ścieżki obok)"</li>
     * </ul>
     * Oraz ref code po dwukropku ({@code primary:DK7}) → " [DK7]".
     */
    public static String translateRoad(String code) {
        // Rozdziel ref (po dwukropku): "primary:DK7" → base="primary", ref="DK7".
        int colonIdx = code.indexOf(':');
        String base = colonIdx > 0 ? code.substring(0, colonIdx) : code;
        String ref = colonIdx > 0 ? code.substring(colonIdx + 1) : null;

        // Wykryj suffix (musi być na końcu base, przed ref).
        String suffix = "";
        if (base.endsWith("_use_sidepath")) {
            suffix = " (obowiązek ścieżki obok)";
            base = base.substring(0, base.length() - "_use_sidepath".length());
        } else if (base.endsWith("_with_cycleway_lane")) {
            suffix = " z pasem rowerowym";
            base = base.substring(0, base.length() - "_with_cycleway_lane".length());
        }

        StringBuilder label = new StringBuilder(ROAD_PL.getOrDefault(base, "inne (" + base + ")"));
        if (ref != null && !ref.isEmpty()) {
            label.append(" [").append(ref).append(']');
        }
        label.append(suffix);
        return label.toString();
    }

    /** Tłumaczy znormalizowany smoothness code na PL. */
    public static String translateSmoothness(String code) {
        return SMOOTHNESS_PL.getOrDefault(code, "inne (" + code + ")");
    }

    @FunctionalInterface
    private interface Translator {
        String apply(String code);
    }

    private static void appendMap(StringBuilder sb, Map<String, Long> byCode, long total, Translator t) {
        if (byCode == null || byCode.isEmpty()) {
            sb.append("  (brak danych)\n");
            return;
        }
        byCode.forEach((k, v) -> sb.append(String.format("  %-38s %6.2f km   %5.1f%%%n",
                t.apply(k), v / 1000.0, 100.0 * v / total)));
    }
}
