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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Routing krawędzi A→B przez BRouter z cache, elevation i wykrywaniem wysp.
public final class EdgeRouter {

    private static final Logger log = LoggerFactory.getLogger(EdgeRouter.class);

    private final EdgeCache cache = new EdgeCache();
    private final BrouterFn brouter;
    private final String profile;
    private final double alpha;
    private final ElevationDataSource elevation;
    private final int parallelism;
    private final Set<String> failedEdges = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> failReasons = new java.util.concurrent.ConcurrentHashMap<>();

    public EdgeRouter(BrouterFn brouter, String profile,
               double alpha, ElevationDataSource elevation, int parallelism) {
        this.brouter = brouter;
        this.profile = profile;
        this.alpha = alpha;
        this.elevation = elevation;
        this.parallelism = Math.max(1, parallelism);
    }

    public EdgeInfo edge(double[] a, double[] b) {
        return cache.getOrCompute(a[0], a[1], b[0], b[1], pts -> {
            List<Waypoint> wps = List.of(
                    new Waypoint(pts[0][0], pts[0][1], null),
                    new Waypoint(pts[1][0], pts[1][1], null));
            try {
                RouteCalculation calc = brouter.route(wps, profile, false);
                cache.onRealCall();
                double km = calc.distanceKm();
                double climbM = 0;
                if (elevation != null) {
                    try { climbM = elevation.sample(calc.coordinates(), calc.coordinates().size()).gainM(); }
                    catch (RuntimeException ignored) { }
                }
                return new EdgeInfo(km, climbM, km + alpha * climbM, calc.coordinates(),
                        calc.crosspointStart(), calc.crosspointEnd());
            } catch (RuntimeException e) {
                cache.onRealCall();
                boolean first = failedEdges.add(GeometryUtil.edgeKey(pts[0], pts[1]));
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String reason = failReason(msg);
                failReasons.merge(reason, 1, Integer::sum);
                if (first) log.warn("Coverage BRouter-FAIL [{}] @ {},{} → {},{} : {}", new Object[]{reason,
                        pts[0][0], pts[0][1], pts[1][0], pts[1][1], msg.length() > 120 ? msg.substring(0, 120) : msg});
                double hav = velomarker.service.planning.WaypointSelector.haversineKm(pts[0], pts[1]);
                return new EdgeInfo(hav * 1.3, 0, hav * 1.3, List.of(pts[0], pts[1]));
            }
        });
    }

    public void prewarm(List<double[]> route) {
        if (route.size() < 3) return;
        List<double[][]> edges = new ArrayList<>(route.size() - 1);
        for (int i = 0; i < route.size() - 1; i++) edges.add(new double[][]{route.get(i), route.get(i + 1)});
        prewarmPairs(edges);
    }

    public void prewarmPairs(List<double[][]> pairs) {
        if (parallelism <= 1 || pairs == null || pairs.isEmpty()) return;
        List<double[][]> edges = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (double[][] p : pairs) {
            String k = String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", p[0][0], p[0][1], p[1][0], p[1][1]);
            if (seen.add(k)) edges.add(p);
        }
        if (edges.size() < 2) return;
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(parallelism);
        io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.current();
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(edges.size());
            for (double[][] e : edges) {
                futures.add(exec.submit(() -> {
                    gate.acquireUninterruptibly();
                    try (var sc = ctx.makeCurrent()) { edge(e[0], e[1]); }
                    catch (RuntimeException ex) { }
                    finally { gate.release(); }
                }));
            }
            for (var f : futures) { try { f.get(); } catch (Exception ignored) { } }
        }
    }

    public <T, R> List<R> parallelMap(java.util.Collection<T> items, java.util.function.Function<T, R> fn) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (parallelism <= 1 || items.size() < 2) {
            List<R> seq = new ArrayList<>(items.size());
            for (T it : items) { R r = fn.apply(it); if (r != null) seq.add(r); }
            return seq;
        }
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(parallelism);
        io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.current();
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<R>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(exec.submit(() -> {
                    gate.acquireUninterruptibly();
                    try (var ignored = ctx.makeCurrent()) { return fn.apply(item); }
                    catch (RuntimeException e) { return null; }
                    finally { gate.release(); }
                }));
            }
            List<R> out = new ArrayList<>(items.size());
            for (var f : futures) { try { R r = f.get(); if (r != null) out.add(r); } catch (Exception ignored) { } }
            return out;
        }
    }

    public void seedSlicedEdgesAtPoint(EdgeInfo full, double[] a, double[] b, int segIdx, double[] point) {
        List<double[]> geom = full.geometry();
        List<double[]> g1 = new ArrayList<>(geom.subList(0, segIdx + 1));
        g1.add(point.clone());
        List<double[]> g2 = new ArrayList<>();
        g2.add(point.clone());
        g2.addAll(geom.subList(segIdx + 1, geom.size()));
        double h1 = GeometryUtil.polyHavKm(g1), h2 = GeometryUtil.polyHavKm(g2);
        double total = Math.max(0.001, h1 + h2);
        double d1 = full.distanceKm() * (h1 / total), d2 = full.distanceKm() * (h2 / total);
        double c1 = full.climbM() * (h1 / total), c2 = full.climbM() * (h2 / total);
        cache.putApproximate(a[0], a[1], point[0], point[1], new EdgeInfo(d1, c1, d1 + alpha * c1, g1, full.crosspointA(), null));
        cache.putApproximate(point[0], point[1], b[0], b[1], new EdgeInfo(d2, c2, d2 + alpha * c2, g2, null, full.crosspointB()));
    }

    public int rerouteApproximateLegs(List<double[]> route) {
        int rerouted = 0;
        cache.setReason("ogonek-relokacja");
        for (int i = 0; i < route.size() - 1; i++) {
            double[] a = route.get(i), b = route.get(i + 1);
            if (!cache.isApproximate(a[0], a[1], b[0], b[1])) continue;
            cache.invalidate(a[0], a[1], b[0], b[1]);
            edge(a, b);
            rerouted++;
        }
        cache.setReason("pomiar");
        return rerouted;
    }

    public void setReason(String reason) { cache.setReason(reason); }
    public long realCalls() { return cache.realCalls(); }
    public long misses() { return cache.misses(); }
    public long hits() { return cache.hits(); }
    public double hitRatio() { return cache.hitRatio(); }
    public Map<String, Long> realCallsByReason() { return cache.realCallsByReason(); }
    public Set<String> failedEdges() { return failedEdges; }
    public Map<String, Integer> failReasons() { return failReasons; }

    private static String failReason(String msg) {
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("island")) return "target-island";
        if (m.contains("not mapped") || m.contains("datafile") || m.contains("not found")) return "not-mapped";
        if (m.contains("timeout") || m.contains("killed") || m.contains("watchdog")) return "timeout";
        return "other";
    }
}
