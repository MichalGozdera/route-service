package velomarker.service;

import velomarker.entity.RouteStats;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe akumulator {@link RouteStats} dla N BRouter calls składających się na jedną
 * "logiczną" trasę (multi-day plan, multi-leg route asystenta, multi-waypoint manual route).
 *
 * <p>Przykład użycia w pętli orchestrator'a:
 * <pre>
 * RouteStatsAccumulator acc = new RouteStatsAccumulator();
 * for (Chunk chunk : chunks) {
 *     RouteCalculation r = brouterClient.calculate(chunk.waypoints, chunk.profile);
 *     acc.add(r.stats());
 * }
 * RouteStats aggregate = acc.build();
 * // → log lub zapis do bazy
 * </pre>
 *
 * <p>Wszystkie {@code add(...)} są synchronized — bezpieczne dla concurrent BRouter calls
 * (np. ALNS2/3 wykonuje paralelne calls z executor service).
 */
public class RouteStatsAccumulator {

    private final AtomicLong totalMeters = new AtomicLong();
    private final Map<String, Long> surface = new TreeMap<>();
    private final Map<String, Long> road = new TreeMap<>();
    private final Map<String, Long> smoothness = new TreeMap<>();

    public synchronized void add(RouteStats other) {
        if (other == null || other.totalMeters() == 0) {
            return;
        }
        totalMeters.addAndGet(other.totalMeters());
        mergeInto(surface, other.surfaceMeters());
        mergeInto(road, other.roadMeters());
        mergeInto(smoothness, other.smoothnessMeters());
    }

    public synchronized RouteStats build() {
        // Spans NIE są łączone w accumulator — to per-call lokalne indeksy do RouteCalculation.coordinates.
        // Klient (FE / orchestrator) trzyma stats per leg jeśli chce kolorować linię na mapie.
        return new RouteStats(totalMeters.get(),
                sortByValueDesc(surface),
                sortByValueDesc(road),
                sortByValueDesc(smoothness),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
    }

    public long totalMeters() {
        return totalMeters.get();
    }

    private static void mergeInto(Map<String, Long> dst, Map<String, Long> src) {
        if (src == null) return;
        src.forEach((k, v) -> dst.merge(k, v, Long::sum));
    }

    private static Map<String, Long> sortByValueDesc(Map<String, Long> in) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>(in.size());
        in.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
