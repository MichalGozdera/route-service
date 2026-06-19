package eu.cokeman.velomarker.out;

import btools.router.OsmTrack;
import velomarker.entity.RouteSpan;
import velomarker.entity.RouteStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Buduje {@link RouteStats} z {@link OsmTrack#messageList} BRoutera — agregowane mapy
 * + listy {@link RouteSpan} per wymiar (do kolorowania linii na FE).
 *
 * <p>Kontrakt kluczy → patrz {@link RouteStats}. Spans są zmergowane (consecutive segmenty
 * z tym samym kodem łączą się w jeden span). Indeksy w {@code startIdx/endIdx} odnoszą się
 * do {@code coordinates} z tego samego BRouter calle.
 */
final class RouteStatsBuilder {

    private RouteStatsBuilder() {
    }

    /**
     * Buduje stats + spans. {@code coords} potrzebne by przemapować endpointy z messageList
     * (mikrostopnie) na indeksy w geometrii.
     */
    static RouteStats build(OsmTrack track, List<double[]> coords) {
        if (track == null || track.messageList == null || track.messageList.size() < 2 || coords == null || coords.size() < 2) {
            return RouteStats.empty();
        }
        final int LON_COL = 0;
        final int LAT_COL = 1;
        final int DIST_COL = 3;
        final int WAYTAGS_COL = 9;

        // Indeks wierzchołków po zaokrąglonych mikrostopniach (forward-pointer matching).
        long[] keyLon = new long[coords.size()];
        long[] keyLat = new long[coords.size()];
        for (int k = 0; k < coords.size(); k++) {
            keyLon[k] = Math.round(coords.get(k)[0] * 1_000_000.0);
            keyLat[k] = Math.round(coords.get(k)[1] * 1_000_000.0);
        }

        Map<String, Long> surfaceByCode = new LinkedHashMap<>();
        Map<String, Long> roadByCode = new LinkedHashMap<>();
        Map<String, Long> smoothnessByCode = new LinkedHashMap<>();
        SpanMerger surfaceSpans = new SpanMerger();
        SpanMerger roadSpans = new SpanMerger();
        SpanMerger smoothnessSpans = new SpanMerger();
        long totalDist = 0;
        int ptr = 0;          // forward-pointer po wierzchołkach
        int prevEndIdx = 0;   // endpoint poprzedniego odcinka

        for (int r = 1; r < track.messageList.size(); r++) {
            String[] row = track.messageList.get(r).split("\t");
            if (row.length <= WAYTAGS_COL) continue;
            long dist = parseLongSafe(row[DIST_COL]);
            if (dist <= 0) continue;

            String wayTags = row[WAYTAGS_COL];
            String surfaceCode = classifySurface(extractTagValue(wayTags, "surface"));
            String roadCode = classifyRoadType(
                    extractTagValue(wayTags, "highway"),
                    extractTagValue(wayTags, "bicycle"),
                    extractTagValue(wayTags, "foot"),
                    extractTagValue(wayTags, "cycleway"),
                    extractTagValue(wayTags, "ref"));
            String smoothnessCode = classifySmoothness(extractTagValue(wayTags, "smoothness"));

            // Agregaty (zawsze, niezależnie od dopasowania endpointu).
            totalDist += dist;
            surfaceByCode.merge(surfaceCode, dist, Long::sum);
            roadByCode.merge(roadCode, dist, Long::sum);
            smoothnessByCode.merge(smoothnessCode, dist, Long::sum);

            // Spans wymagają dopasowania endpointu do indeksu wierzchołka.
            long mLon = parseLongSafe(row[LON_COL]);
            long mLat = parseLongSafe(row[LAT_COL]);
            int endIdx = -1;
            for (int k = ptr; k < coords.size(); k++) {
                if (keyLon[k] == mLon && keyLat[k] == mLat) {
                    endIdx = k;
                    break;
                }
            }
            if (endIdx > prevEndIdx) {
                surfaceSpans.append(prevEndIdx, endIdx, surfaceCode);
                roadSpans.append(prevEndIdx, endIdx, roadCode);
                smoothnessSpans.append(prevEndIdx, endIdx, smoothnessCode);
                ptr = endIdx;
                prevEndIdx = endIdx;
            }
            // Gdy endIdx<0 albo <=prevEndIdx, span niedoklejony — agregaty (meters) i tak liczone wyżej.
        }

        return new RouteStats(totalDist,
                sortByValueDesc(surfaceByCode),
                sortByValueDesc(roadByCode),
                sortByValueDesc(smoothnessByCode),
                surfaceSpans.build(),
                roadSpans.build(),
                smoothnessSpans.build());
    }

    private static String classifySurface(String tag) {
        if (tag == null || tag.isEmpty()) return "unknown";
        return tag.toLowerCase(Locale.ROOT);
    }

    private static String classifySmoothness(String tag) {
        if (tag == null || tag.isEmpty()) return "unknown";
        return tag.toLowerCase(Locale.ROOT);
    }

    private static String classifyRoadType(String highway, String bicycle, String foot, String cycleway, String ref) {
        if (highway == null) return "unknown";

        boolean bikeDesignated = "designated".equals(bicycle);
        boolean footDesignated = "designated".equals(foot);
        boolean bikeAllowed = bikeDesignated || "yes".equals(bicycle) || "permissive".equals(bicycle);
        boolean footAllowed = footDesignated || "yes".equals(foot) || "permissive".equals(foot);

        switch (highway) {
            case "cycleway":
                return footAllowed ? "cycleway_shared_foot" : "cycleway";
            case "path":
                if (bikeDesignated && footDesignated) return "path_bike_foot";
                if (bikeDesignated) return "path_bike";
                if (footDesignated) return "path_foot";
                return "path";
            case "footway":
                return bikeAllowed ? "footway_bike_allowed" : "footway";
            case "pedestrian":
                return bikeAllowed ? "pedestrian_bike_allowed" : "pedestrian";
            case "track":
                return "track";
            case "bridleway":
                return "bridleway";
            case "steps":
                return "steps";
        }

        String baseClass = highway.endsWith("_link") ? highway.substring(0, highway.length() - 5) : highway;
        StringBuilder code = new StringBuilder(baseClass);

        if ("use_sidepath".equals(bicycle)) {
            code.append("_use_sidepath");
        } else if ("track".equals(cycleway) || "lane".equals(cycleway)) {
            code.append("_with_cycleway_lane");
        }

        if (ref != null && !ref.isEmpty()) {
            String firstRef = ref.split("[;,]", 2)[0].trim();
            if (!firstRef.isEmpty()) {
                code.append(":").append(firstRef);
            }
        }
        return code.toString();
    }

    private static Map<String, Long> sortByValueDesc(Map<String, Long> in) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>(in.size());
        in.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static String extractTagValue(String wayTags, String key) {
        if (wayTags == null || wayTags.isEmpty()) return null;
        String prefix = key + "=";
        for (String tok : wayTags.split(" ")) {
            if (tok.startsWith(prefix)) {
                String val = tok.substring(prefix.length());
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Pomocnik: gromadzi {@link RouteSpan} mergując consecutive odcinki z tym samym kodem. */
    private static final class SpanMerger {
        private final List<RouteSpan> spans = new ArrayList<>();

        void append(int startIdx, int endIdx, String code) {
            if (!spans.isEmpty()) {
                RouteSpan last = spans.get(spans.size() - 1);
                if (last.endIdx() == startIdx && last.code().equals(code)) {
                    spans.set(spans.size() - 1, new RouteSpan(last.startIdx(), endIdx, code));
                    return;
                }
            }
            spans.add(new RouteSpan(startIdx, endIdx, code));
        }

        List<RouteSpan> build() {
            return spans;
        }
    }
}
