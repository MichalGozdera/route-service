package velomarker.service;

import velomarker.entity.RouteSpan;
import velomarker.entity.RouteStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wycina {@link RouteStats} dla podzakresu indeksów {@code [startIdx, endIdx]} z full-route stats
 * — używane gdy multi-day wyprawa jest dzielona na dni z jednej scalonej geometrii.
 *
 * <p>Spans przycinane do okna + przesuwane do lokalnej przestrzeni dnia (startIdx → 0).
 * Meters per kod liczone z długości segmentów wewnątrz okna (Haversine na coordinates).
 */
public final class RouteStatsSlicer {

    private RouteStatsSlicer() {
    }

    public static RouteStats slice(RouteStats full, List<double[]> coords, int startIdx, int endIdx) {
        if (full == null || coords == null || startIdx < 0 || endIdx >= coords.size() || endIdx <= startIdx) {
            return RouteStats.empty();
        }
        // Pre-compute cumulative meters dla [startIdx..endIdx] by szybko liczyć długość per span.
        double[] cum = new double[endIdx - startIdx + 1];
        for (int i = 1; i < cum.length; i++) {
            cum[i] = cum[i - 1] + haversineM(coords.get(startIdx + i - 1), coords.get(startIdx + i));
        }
        long totalMeters = (long) Math.round(cum[cum.length - 1]);
        if (totalMeters == 0) return RouteStats.empty();

        Map<String, Long> surfaceMeters = new LinkedHashMap<>();
        Map<String, Long> roadMeters = new LinkedHashMap<>();
        Map<String, Long> smoothnessMeters = new LinkedHashMap<>();
        List<RouteSpan> surfaceSpans = new ArrayList<>();
        List<RouteSpan> roadSpans = new ArrayList<>();
        List<RouteSpan> smoothnessSpans = new ArrayList<>();

        sliceDim(full.surfaceSpans(), startIdx, endIdx, cum, surfaceMeters, surfaceSpans);
        sliceDim(full.roadSpans(), startIdx, endIdx, cum, roadMeters, roadSpans);
        sliceDim(full.smoothnessSpans(), startIdx, endIdx, cum, smoothnessMeters, smoothnessSpans);

        return new RouteStats(totalMeters,
                sortDesc(surfaceMeters),
                sortDesc(roadMeters),
                sortDesc(smoothnessMeters),
                surfaceSpans, roadSpans, smoothnessSpans);
    }

    private static void sliceDim(List<RouteSpan> fullSpans, int startIdx, int endIdx, double[] cum,
                                 Map<String, Long> metersOut, List<RouteSpan> spansOut) {
        if (fullSpans == null || fullSpans.isEmpty()) return;
        for (RouteSpan sp : fullSpans) {
            int s = Math.max(sp.startIdx(), startIdx);
            int e = Math.min(sp.endIdx(), endIdx);
            if (e <= s) continue;
            int localS = s - startIdx;
            int localE = e - startIdx;
            double meters = cum[localE] - cum[localS];
            if (meters > 0) {
                metersOut.merge(sp.code(), (long) Math.round(meters), Long::sum);
                spansOut.add(new RouteSpan(localS, localE, sp.code()));
            }
        }
    }

    private static Map<String, Long> sortDesc(Map<String, Long> in) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>(in.size());
        in.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static double haversineM(double[] a, double[] b) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(b[1] - a[1]);
        double dLng = Math.toRadians(b[0] - a[0]);
        double s1 = Math.sin(dLat / 2);
        double s2 = Math.sin(dLng / 2);
        double x = s1 * s1 + Math.cos(Math.toRadians(a[1])) * Math.cos(Math.toRadians(b[1])) * s2 * s2;
        return r * 2 * Math.asin(Math.sqrt(x));
    }
}
