package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Routing krawędzi przez BRouter z cache i wykrywaniem wysp. Jedna odpowiedzialność: „policz/odczytaj
 * krawędź A→B". Owija {@link EdgeCache} + wywołanie BRoutera + elevation + księgowanie failów.
 * Instancja per plan (stąd thread-safe — własny cache i zbiory failów).
 */
final class EdgeRouter {

    private static final Logger log = LoggerFactory.getLogger(EdgeRouter.class);
    /** Tolerancja snapu punktu do wierzchołka geometrii przy slice (km, ~50m). */
    private static final double SLICE_SNAP_KM = 0.05;

    private final EdgeCache cache = new EdgeCache();
    private final BiFunction<List<Waypoint>, String, RouteCalculation> brouter;
    private final String profile;
    private final double alpha;
    private final ElevationDataSource elevation;
    private final int parallelism;
    /** Krawędzie, dla których BRouter rzucił (target-island / nieosiągalne) — sygnał „wyspa". */
    private final Set<String> failedEdges = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Powody failów BRoutera (target-island / not-mapped / timeout / other) → ile. */
    private final Map<String, Integer> failReasons = new java.util.concurrent.ConcurrentHashMap<>();

    EdgeRouter(BiFunction<List<Waypoint>, String, RouteCalculation> brouter, String profile,
               double alpha, ElevationDataSource elevation, int parallelism) {
        this.brouter = brouter;
        this.profile = profile;
        this.alpha = alpha;
        this.elevation = elevation;
        this.parallelism = Math.max(1, parallelism);
    }

    /** EdgeInfo (z geometrią) dla A→B z cache; miss = 1 BRouter call (2-punktowy) + elevation. */
    EdgeCache.EdgeInfo edge(double[] a, double[] b) {
        return cache.getOrCompute(a[0], a[1], b[0], b[1], pts -> {
            List<Waypoint> wps = List.of(
                    new Waypoint(pts[0][0], pts[0][1], null),
                    new Waypoint(pts[1][0], pts[1][1], null));
            try {
                RouteCalculation calc = brouter.apply(wps, profile);
                cache.onRealCall();
                double km = calc.distanceKm();
                double climbM = 0;
                if (elevation != null) {
                    try { climbM = elevation.sample(calc.coordinates(), calc.coordinates().size()).gainM(); }
                    catch (RuntimeException ignored) { /* brak DEM dla edge → 0 climb */ }
                }
                return new EdgeCache.EdgeInfo(km, climbM, km + alpha * climbM, calc.coordinates());
            } catch (RuntimeException e) {
                cache.onRealCall();
                // BRouter nie policzył (target-island / brak drogi) → zapamiętaj jako wyspę (seed prune usunie wp).
                boolean first = failedEdges.add(GeometryUtil.edgeKey(pts[0], pts[1]));
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String reason = failReason(msg);
                failReasons.merge(reason, 1, Integer::sum);
                if (first) log.warn("Coverage BRouter-FAIL [{}] @ {},{} → {},{} : {}", new Object[]{reason,
                        pts[0][0], pts[0][1], pts[1][0], pts[1][1], msg.length() > 120 ? msg.substring(0, 120) : msg});
                double hav = velomarker.service.planning.WaypointSelector.haversineKm(pts[0], pts[1]);
                return new EdgeCache.EdgeInfo(hav * 1.3, 0, hav * 1.3, List.of(pts[0], pts[1]));
            }
        });
    }

    /** Policz NIEcache'owane krawędzie trasy RÓWNOLEGLE (do {@code parallelism} naraz). */
    void prewarm(List<double[]> route) {
        if (route.size() < 3) return;
        List<double[][]> edges = new ArrayList<>(route.size() - 1);
        for (int i = 0; i < route.size() - 1; i++) edges.add(new double[][]{route.get(i), route.get(i + 1)});
        prewarmPairs(edges);
    }

    /** Równoległy pre-warm dowolnej listy par A→B (dedup po kierunkowym kluczu). */
    void prewarmPairs(List<double[][]> pairs) {
        if (parallelism <= 1 || pairs == null || pairs.isEmpty()) return;
        List<double[][]> edges = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (double[][] p : pairs) {
            String k = String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", p[0][0], p[0][1], p[1][0], p[1][1]);
            if (seen.add(k)) edges.add(p);
        }
        if (edges.size() < 2) return;
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(parallelism);
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(edges.size());
            for (double[][] e : edges) {
                futures.add(exec.submit(() -> {
                    gate.acquireUninterruptibly();
                    try { edge(e[0], e[1]); }
                    catch (RuntimeException ignored) { /* fallback haversine w edge() */ }
                    finally { gate.release(); }
                }));
            }
            for (var f : futures) { try { f.get(); } catch (Exception ignored) { /* best-effort */ } }
        }
    }

    /** Tnie pełną krawędź na podścieżkę do/od {@code newWp} (slice na geometrii, 0 BRouter) i cache'uje ją. */
    EdgeCache.EdgeInfo sliceDepart(EdgeCache.EdgeInfo full, double[] a, double[] b, double[] newWp, boolean forward) {
        List<double[]> geom = full.geometry();
        int m = GeometryUtil.nearestVertexIdx(geom, newWp);
        if (m <= 0 || m >= geom.size() - 1) return null;
        if (velomarker.service.planning.WaypointSelector.haversineKm(geom.get(m), newWp) > SLICE_SNAP_KM) return null;
        List<double[]> g2;
        if (forward) {
            g2 = new ArrayList<>(geom.size() - m + 1);
            g2.add(newWp.clone());
            g2.addAll(geom.subList(m, geom.size()));
        } else {
            g2 = new ArrayList<>(m + 2);
            g2.addAll(geom.subList(0, m + 1));
            g2.add(newWp.clone());
        }
        double hFull = Math.max(0.001, GeometryUtil.polyHavKm(geom));
        double h2 = GeometryUtil.polyHavKm(g2);
        double d2 = full.distanceKm() * (h2 / hFull);
        double c2 = full.climbM() * (h2 / hFull);
        EdgeCache.EdgeInfo e2 = new EdgeCache.EdgeInfo(d2, c2, d2 + alpha * c2, g2);
        if (forward) cache.getOrCompute(newWp[0], newWp[1], b[0], b[1], pts -> e2);
        else cache.getOrCompute(a[0], a[1], newWp[0], newWp[1], pts -> e2);
        return e2;
    }

    /** Tnie EdgeInfo na wierzchołku i SEEDUJE oba sub-edge w cache (0 BRouter, slice po geometrii). */
    void seedSlicedEdges(EdgeCache.EdgeInfo full, double[] a, double[] b, int splitIdx) {
        List<double[]> geom = full.geometry();
        List<double[]> g1 = new ArrayList<>(geom.subList(0, splitIdx + 1));
        List<double[]> g2 = new ArrayList<>(geom.subList(splitIdx, geom.size()));
        double h1 = GeometryUtil.polyHavKm(g1);
        double h2 = GeometryUtil.polyHavKm(g2);
        double total = Math.max(0.001, h1 + h2);
        double d1 = full.distanceKm() * (h1 / total);
        double d2 = full.distanceKm() * (h2 / total);
        double c1 = full.climbM() * (h1 / total);
        double c2 = full.climbM() * (h2 / total);
        double[] p = geom.get(splitIdx);
        cache.putApproximate(a[0], a[1], p[0], p[1], new EdgeCache.EdgeInfo(d1, c1, d1 + alpha * c1, g1));
        cache.putApproximate(p[0], p[1], b[0], b[1], new EdgeCache.EdgeInfo(d2, c2, d2 + alpha * c2, g2));
    }

    /** Jak {@link #seedSlicedEdges}, ale split w dowolnym {@code point} na segmencie {@code segIdx} (REAL, nie approx). */
    void seedSlicedEdgesAtPoint(EdgeCache.EdgeInfo full, double[] a, double[] b, int segIdx, double[] point) {
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
        cache.getOrCompute(a[0], a[1], point[0], point[1], pts -> new EdgeCache.EdgeInfo(d1, c1, d1 + alpha * c1, g1));
        cache.getOrCompute(point[0], point[1], b[0], b[1], pts -> new EdgeCache.EdgeInfo(d2, c2, d2 + alpha * c2, g2));
    }

    /** Po cięciu spurów przeroutuj REALNIE legi zasilone slicem (przybliżenie) → ujawnia wtórniaki. */
    int rerouteApproximateLegs(List<double[]> route) {
        int rerouted = 0;
        cache.setReason("ogonek-relokacja");
        for (int i = 0; i < route.size() - 1; i++) {
            double[] a = route.get(i), b = route.get(i + 1);
            if (!cache.isApproximate(a[0], a[1], b[0], b[1])) continue;
            EdgeCache.EdgeInfo slice = edge(a, b);
            double hav = velomarker.service.planning.WaypointSelector.haversineKm(a, b);
            if (slice.distanceKm() <= 1.3 * Math.max(0.05, hav)) continue;
            cache.invalidate(a[0], a[1], b[0], b[1]);
            EdgeCache.EdgeInfo real = edge(a, b);
            if (real.distanceKm() < 0.97 * slice.distanceKm()) rerouted++;
        }
        cache.setReason("pomiar");
        return rerouted;
    }

    /** Cache'uj gotowe EdgeInfo dla A→B (gdy wartość policzono poza routerem, np. re-kotwica). */
    void cacheEdge(double[] a, double[] b, EdgeCache.EdgeInfo info) {
        cache.getOrCompute(a[0], a[1], b[0], b[1], pts -> info);
    }

    // ── pass-through do statystyk cache (księgowanie strzałów) ──
    void setReason(String reason) { cache.setReason(reason); }
    long realCalls() { return cache.realCalls(); }
    long misses() { return cache.misses(); }
    long hits() { return cache.hits(); }
    double hitRatio() { return cache.hitRatio(); }
    Map<String, Long> realCallsByReason() { return cache.realCallsByReason(); }
    Set<String> failedEdges() { return failedEdges; }
    Map<String, Integer> failReasons() { return failReasons; }
    void resetFailTracking() { failedEdges.clear(); failReasons.clear(); }

    /** Klasyfikacja komunikatu błędu BRoutera na powód (do logu/agregatu). */
    private static String failReason(String msg) {
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("island")) return "target-island";
        if (m.contains("not mapped") || m.contains("datafile") || m.contains("not found")) return "not-mapped";
        if (m.contains("timeout") || m.contains("killed") || m.contains("watchdog")) return "timeout";
        return "other";
    }
}
