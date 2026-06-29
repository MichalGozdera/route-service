package velomarker.service.planning.coverage.metric;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

// Cache wyników BRouter calls dla pojedynczych krawędzi A→B.
public class EdgeCache {

    private final ConcurrentHashMap<String, EdgeInfo> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final java.util.Set<String> approximate = ConcurrentHashMap.newKeySet();

    private volatile String reason = "inne";
    private final AtomicLong realCalls = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> realByReason = new ConcurrentHashMap<>();

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

    private static String key(double ax, double ay, double bx, double by) {
        return String.format(Locale.ROOT, "%.5f,%.5f→%.5f,%.5f", ax, ay, bx, by);
    }

    public void putApproximate(double ax, double ay, double bx, double by, EdgeInfo info) {
        String k = key(ax, ay, bx, by);
        cache.put(k, info);
        approximate.add(k);
        misses.incrementAndGet();
    }

    public boolean isApproximate(double ax, double ay, double bx, double by) {
        return approximate.contains(key(ax, ay, bx, by));
    }

    public void invalidate(double ax, double ay, double bx, double by) {
        String k = key(ax, ay, bx, by);
        cache.remove(k);
        approximate.remove(k);
    }

    public void setReason(String reason) { this.reason = reason == null ? "inne" : reason; }

    public void onRealCall() {
        realCalls.incrementAndGet();
        realByReason.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    public long realCalls() { return realCalls.get(); }

    public java.util.Map<String, Long> realCallsByReason() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        realByReason.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }
}
