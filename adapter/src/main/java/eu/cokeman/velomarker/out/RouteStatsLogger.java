package eu.cokeman.velomarker.out;

import btools.router.OsmTrack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Logger statystyk trasy z BRouter {@link OsmTrack}. Czyta {@code messageList} (tab-separated wiersze
 * per-segment) i agreguje WayTags wagowane dystansem. Outputuje:
 * <ul>
 *   <li>{@code INFO}: agregaty po kluczach (highway, surface, tracktype itd.) z metrami i procentami,
 *       plus per-key bucket {@code [no tag]} dla segmentów, które nie mają danego klucza,</li>
 *   <li>{@code DEBUG}: pełen dump per-segment (dystans + WayTags + NodeTags).</li>
 * </ul>
 * Cel: po wyznaczeniu trasy widzieć JAKIMI drogami się jedzie (asfalt/szuter/ścieżka, primary/track
 * itd.). Dane potem mogą zasilić raport na froncie — narazie loguj.
 *
 * <p>Format kolumn BRoutera 1.7.9 ({@code OsmTrack.aggregateMessages()}):
 * {@code Longitude  Latitude  Elevation  Distance  CostPerKm  ElevCost  TurnCost  NodeCost  InitialCost  WayTags  NodeTags  Time  Energy}.
 * Distance jest w metrach jako int-string.
 */
final class RouteStatsLogger {

    private RouteStatsLogger() {
    }

    static void log(Logger log, OsmTrack track, String profile) {
        if (track == null) {
            log.warn("RouteStatsLogger: track is null");
            return;
        }
        if (track.messageList == null || track.messageList.isEmpty()) {
            log.warn("RouteStatsLogger: track.messageList is empty (size={}). " +
                    "BRouter has not generated per-segment messages — check RoutingContext flags.",
                    track.messageList == null ? "null" : 0);
            return;
        }
        if (track.messageList.size() < 2) {
            log.warn("RouteStatsLogger: track.messageList has only header, no data rows. First row: '{}'",
                    track.messageList.get(0));
            return;
        }
        String header0 = track.messageList.get(0);
        String[] header = header0.split("\t");
        int distCol = idx(header, "Distance");
        int wayTagsCol = idx(header, "WayTags");
        int nodeTagsCol = idx(header, "NodeTags");
        if (distCol < 0 || wayTagsCol < 0) {
            log.warn("RouteStatsLogger: missing required columns (Distance={}, WayTags={}) in header: '{}'",
                    distCol, wayTagsCol, header0);
            return;
        }

        Map<String, Map<String, Long>> aggByKey = new TreeMap<>();
        List<String> segments = new ArrayList<>(track.messageList.size() - 1);
        long totalDistMutable = 0;

        for (int r = 1; r < track.messageList.size(); r++) {
            String[] row = track.messageList.get(r).split("\t");
            long dist = safeParseLong(cell(row, distCol));
            if (dist <= 0) {
                continue;
            }
            totalDistMutable += dist;
            String wayTags = cell(row, wayTagsCol);
            String nodeTags = nodeTagsCol >= 0 ? cell(row, nodeTagsCol) : "";

            segments.add(String.format("[seg %3d] %6d m  way=[%s]%s",
                    r, dist, wayTags, nodeTags.isEmpty() ? "" : "  node=[" + nodeTags + "]"));

            for (String tok : wayTags.split(" ")) {
                int eq = tok.indexOf('=');
                if (eq <= 0 || eq == tok.length() - 1) continue;
                String key = tok.substring(0, eq);
                String val = tok.substring(eq + 1);
                aggByKey.computeIfAbsent(key, k -> new HashMap<>()).merge(val, dist, Long::sum);
            }
        }

        if (totalDistMutable == 0) {
            return;
        }
        final long totalDist = totalDistMutable;

        StringBuilder sb = new StringBuilder("\n");
        sb.append("========== BRouter route stats ==========\n");
        sb.append(String.format("profile : %s%n", profile));
        sb.append(String.format("total   : %.3f km  (%d segments)%n", totalDist / 1000.0, segments.size()));
        sb.append('\n');

        for (Map.Entry<String, Map<String, Long>> keyEntry : aggByKey.entrySet()) {
            String key = keyEntry.getKey();
            Map<String, Long> values = keyEntry.getValue();
            long tagged = values.values().stream().mapToLong(Long::longValue).sum();
            long untagged = Math.max(0, totalDist - tagged);

            sb.append(String.format("[%s]%n", key));
            values.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .forEach(v -> sb.append(formatLine(v.getKey(), v.getValue(), totalDist)));
            if (untagged > 0) {
                sb.append(formatLine("[no tag]", untagged, totalDist));
            }
            sb.append('\n');
        }

        sb.append("==========================================");
        log.debug(sb.toString());

        if (log.isTraceEnabled()) {
            StringBuilder seg = new StringBuilder("\n--- per-segment dump (").append(segments.size()).append(" segs) ---\n");
            segments.forEach(s -> seg.append(s).append('\n'));
            seg.append("------------------------------------------");
            log.trace(seg.toString());
        }
    }

    private static String formatLine(String value, long meters, long total) {
        return String.format("  %-20s %7d m   %5.1f%%%n", value, meters, 100.0 * meters / total);
    }

    private static int idx(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i])) return i;
        }
        return -1;
    }

    private static String cell(String[] row, int col) {
        if (col < 0 || col >= row.length || row[col] == null) {
            return "";
        }
        return row[col].trim();
    }

    private static long safeParseLong(String s) {
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return Math.round(Double.parseDouble(s));
            } catch (NumberFormatException e2) {
                return 0L;
            }
        }
    }
}
