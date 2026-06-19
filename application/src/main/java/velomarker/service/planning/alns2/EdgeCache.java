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
    /** v3.30: klucze legów zasilonych SLICEM (przybliżenie kształtu starej nogi, NIE realny BRouter
     * dla tej pary). {@code rerouteApproximateLegs} przeroutowuje je realnie po cięciu spurów. */
    private final java.util.Set<String> approximate = ConcurrentHashMap.newKeySet();

    /**
     * LEDGER STRZAŁÓW (v3.16) — liczy REALNE wywołania BRoutera per powód. NIE używamy do tego
     * {@link #misses}: miss inkrementuje się też dla {@code seedSlicedEdges} (loader zwraca gotowy
     * EdgeInfo bez BRoutera), więc misses ZAWYŻA realne strzały. {@link #onRealCall()} woła loader
     * DOKŁADNIE w punkcie {@code brouter.apply}. {@code reason} ustawiany na wejściu fazy
     * ({@link #setReason}); volatile = widoczność dla równoległego pre-warmu (cała faza ma jeden powód).
     */
    private volatile String reason = "inne";
    private final AtomicLong realCalls = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> realByReason = new ConcurrentHashMap<>();

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

    /** v3.30: wstaw EdgeInfo zasilony SLICEM i oznacz jako approximate (do późniejszego re-route real). */
    public void putApproximate(double ax, double ay, double bx, double by, EdgeInfo info) {
        String k = key(ax, ay, bx, by);
        cache.put(k, info);
        approximate.add(k);
        misses.incrementAndGet(); // jak getOrCompute (sliced-seed: miss, ale BEZ realCall)
    }

    /** v3.30: czy (A→B) to sliced-przybliżenie (nie realny BRouter). */
    public boolean isApproximate(double ax, double ay, double bx, double by) {
        return approximate.contains(key(ax, ay, bx, by));
    }

    /** v3.30: usuń wpis (A→B) z cache — kolejny getOrCompute przeliczy go REALNIE (BRouter). */
    public void invalidate(double ax, double ay, double bx, double by) {
        String k = key(ax, ay, bx, by);
        cache.remove(k);
        approximate.remove(k);
    }

    /** Ustawia powód kolejnych realnych strzałów BRoutera (na wejściu fazy seeda). */
    public void setReason(String reason) { this.reason = reason == null ? "inne" : reason; }

    /** Woła loader DOKŁADNIE przy {@code brouter.apply} — księguje realny strzał pod bieżącym powodem. */
    public void onRealCall() {
        realCalls.incrementAndGet();
        realByReason.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    /** Realne strzały BRoutera w tym planie (bez sliced-seedów, które tylko zasilają cache). */
    public long realCalls() { return realCalls.get(); }

    /** Snapshot rozbicia realnych strzałów per powód (do końcowego rollupu). */
    public java.util.Map<String, Long> realCallsByReason() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        realByReason.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public int size() { return cache.size(); }
    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }
}
