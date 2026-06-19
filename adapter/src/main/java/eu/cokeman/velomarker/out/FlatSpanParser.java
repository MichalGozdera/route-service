package eu.cokeman.velomarker.out;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser embedded BRouter {@code track.messageList} → zakresy indeksów wierzchołków leżących w tunelach/wiaduktach.
 * Każdy element {@code messageList} to linia tab-separated kolumn (pierwszy wiersz to nagłówek z nazwami).
 * Format wspólny z HTTP brouter ({@code properties.messages}), tylko inna postać surowych danych:
 * <ul>
 *   <li>HTTP: JSON {@code [[header_col1, header_col2,...], [v1,v2,...], ...]}</li>
 *   <li>embedded: {@code List<String>} z każdym wierszem rozdzielonym {@code \t}</li>
 * </ul>
 * Logika dopasowania endpointów do indeksów wierzchołków identyczna jak w
 * {@link HttpBrouterRoutingClient#parseFlatSpans} — endpointy są dokładnymi
 * wierzchołkami geometrii (mikrostopnie/1e6 == coord), forward-pointer bez dryfu.
 */
final class FlatSpanParser {

    private FlatSpanParser() {
    }

    static List<int[]> parse(List<String> messageList, List<double[]> coords) {
        if (messageList == null || messageList.size() < 2 || coords.size() < 3) {
            return List.of();
        }
        String[] header = messageList.get(0).split("\t");
        int lonCol = columnIndex(header, "Longitude");
        int latCol = columnIndex(header, "Latitude");
        int tagsCol = columnIndex(header, "WayTags");
        if (lonCol < 0 || latCol < 0 || tagsCol < 0) {
            return List.of();
        }

        long[] keyLon = new long[coords.size()];
        long[] keyLat = new long[coords.size()];
        for (int k = 0; k < coords.size(); k++) {
            keyLon[k] = Math.round(coords.get(k)[0] * 1_000_000.0);
            keyLat[k] = Math.round(coords.get(k)[1] * 1_000_000.0);
        }

        List<int[]> spans = new ArrayList<>();
        int ptr = 0;
        int prevEndIdx = 0;
        for (int r = 1; r < messageList.size(); r++) {
            String[] row = messageList.get(r).split("\t");
            long mLon = parseLongCell(row, lonCol);
            long mLat = parseLongCell(row, latCol);
            int endIdx = -1;
            for (int k = ptr; k < coords.size(); k++) {
                if (keyLon[k] == mLon && keyLat[k] == mLat) {
                    endIdx = k;
                    break;
                }
            }
            if (endIdx < 0) {
                // Endpoint nie odnaleziony — rezygnujemy z korekcji tuneli (best-effort).
                return List.of();
            }
            if (isTunnelOrBridge(textCell(row, tagsCol)) && endIdx > prevEndIdx) {
                addOrMergeSpan(spans, prevEndIdx, endIdx);
            }
            ptr = endIdx;
            prevEndIdx = endIdx;
        }
        return spans;
    }

    private static void addOrMergeSpan(List<int[]> spans, int a, int b) {
        if (!spans.isEmpty()) {
            int[] last = spans.get(spans.size() - 1);
            if (a <= last[1]) {
                last[1] = Math.max(last[1], b);
                return;
            }
        }
        spans.add(new int[]{a, b});
    }

    private static boolean isTunnelOrBridge(String wayTags) {
        if (wayTags == null || wayTags.isEmpty()) {
            return false;
        }
        for (String token : wayTags.split(" ")) {
            if ((token.startsWith("tunnel=") || token.startsWith("bridge=")) && !token.endsWith("=no")) {
                return true;
            }
        }
        return false;
    }

    private static int columnIndex(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i])) {
                return i;
            }
        }
        return -1;
    }

    private static long parseLongCell(String[] row, int col) {
        try {
            return Long.parseLong(textCell(row, col).trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String textCell(String[] row, int col) {
        if (col < 0 || col >= row.length) {
            return "";
        }
        return row[col] == null ? "" : row[col];
    }
}
