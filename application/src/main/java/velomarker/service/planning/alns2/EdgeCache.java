package velomarker.service.planning.alns2;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Cache wyników BRouter calls dla pojedynczych edge'y (A→B).
 *
 * <p>ALNS2 robi SA z 200 iteracjami, każda destroy/repair sprawdza wiele wstawek.
 * Każda wstawka X między A i B wymaga 3 BRouter calls (A→X, X→B, A→B). Bez cache to
 * 10k+ BRouter calls per plan = nie do zaakceptowania. Z cache: setki unique calls.
 *
 * <p>Klucz = 5-decimal precision (~1m). Insertion-safe (ConcurrentHashMap).
 *
 * @see Alns2Parameters
 */
public class EdgeCache {

    public record EdgeInfo(double distanceKm, double climbM, double effort, java.util.List<double[]> geometry) {
        /** 3-arg bez geometrii (testy / fallbacky które nie potrzebują polyline). */
        public EdgeInfo(double distanceKm, double climbM, double effort) {
            this(distanceKm, climbM, effort, java.util.List.of());
        }
    }

    private final ConcurrentHashMap<String, EdgeInfo> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /**
     * Lookup-or-compute. Jeśli (A→B) jest w cache, zwraca; inaczej wywołuje loader.
     *
     * @param ax,ay lng/lat punktu A
     * @param bx,by lng/lat punktu B
     * @param loader funkcja licząca EdgeInfo (BRouter call + elevation sample)
     */
    public EdgeInfo getOrCompute(double ax, double ay, double bx, double by,
                                  Function<double[][], EdgeInfo> loader) {
        String key = key(ax, ay, bx, by);
        EdgeInfo cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        EdgeInfo computed = loader.apply(new double[][]{{ax, ay}, {bx, by}});
        cache.put(key, computed);
        return computed;
    }

    /** Klucz: A→B (kierunkowy, bo BRouter daje różne ścieżki dla różnych kierunków). */
    private static String key(double ax, double ay, double bx, double by) {
        return String.format(Locale.ROOT, "%.5f,%.5f→%.5f,%.5f", ax, ay, bx, by);
    }

    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public int size() { return cache.size(); }
    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }
}
