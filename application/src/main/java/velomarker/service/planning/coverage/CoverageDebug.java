package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renderery ADMIN-DEBUG dla seeda (gated {@code debugGeoJson}): GeoJSON snapshot trasy/szkieletu na fazę,
 * linia ROUTE-STATS (dist/wznios/effort vs budżet + gminy per kategoria) oraz rollup STRZAŁÓW BRoutera per
 * faza. Jedna odpowiedzialność: diagnostyka. Stan strzałów ({@code shots}) trzymany tu, akumulowany per plan.
 */
final class CoverageDebug {

    private static final Logger log = LoggerFactory.getLogger(CoverageDebug.class);

    private final boolean debugGeoJson;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
    private final CoveragePlannerParameters params;
    private final double budget;
    private final Map<Integer, String> areaCat;
    /** STRZAŁY/plan per faza (suma + Δfaza) — akumulator per plan. */
    private Map<String, Long> shots = new java.util.HashMap<>();

    CoverageDebug(boolean debugGeoJson, EdgeRouter edgeRouter, RouteMetrics metrics, GminaIndex gminaIndex,
                  CoveragePlannerParameters params, double budget, Map<Integer, String> areaCat) {
        this.debugGeoJson = debugGeoJson;
        this.edgeRouter = edgeRouter;
        this.metrics = metrics;
        this.gminaIndex = gminaIndex;
        this.params = params;
        this.budget = budget;
        this.areaCat = areaCat;
    }

    void resetShots() { shots = new java.util.HashMap<>(); }

    /** Rollup STRZAŁÓW BRoutera per powód dla danej fazy (Δ względem poprzedniej). Aktualizuje wewnętrzny snapshot. */
    void logShots(String phase) {
        Map<String, Long> now = new java.util.HashMap<>(edgeRouter.realCallsByReason());
        long total = edgeRouter.realCalls(), prevTotal = shots.getOrDefault("RAZEM", 0L);
        java.util.function.BiFunction<String, Long, String> f =
                (k, n) -> n + "(Δ" + (n - shots.getOrDefault(k, 0L)) + ")";
        log.info("Coverage STRZAŁY/plan [{}]: grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} | RAZEM={} (Δfaza={})",
                new Object[]{phase,
                        f.apply("grow", now.getOrDefault("grow", 0L)),
                        f.apply("ogonek-relokacja", now.getOrDefault("ogonek-relokacja", 0L)),
                        f.apply("ogonek-scalenie", now.getOrDefault("ogonek-scalenie", 0L)),
                        f.apply("dziura-otoczona", now.getOrDefault("dziura-otoczona", 0L)),
                        f.apply("dziura-przy-trasie", now.getOrDefault("dziura-przy-trasie", 0L)),
                        f.apply("pomiar", now.getOrDefault("pomiar", 0L)),
                        total, total - prevTotal});
        now.put("RAZEM", total);
        shots = now;
    }

    void skeleton(String phase, List<double[]> route) {
        if (!debugGeoJson || route == null || route.isEmpty()) return;
        StringBuilder sb = new StringBuilder(route.size() * 48);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        sb.append("{\"type\":\"Feature\",\"properties\":{\"phase\":\"").append(phase)
                .append("\",\"kind\":\"skeleton\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        appendCoords(sb, route);
        sb.append("]}}");
        for (int i = 0; i < route.size(); i++) {
            sb.append(",{\"type\":\"Feature\",\"properties\":{\"idx\":").append(i)
                    .append(",\"phase\":\"").append(phase).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            appendPt(sb, route.get(i));
            sb.append("]}}");
        }
        sb.append("]}");
        System.out.println("dupa");
       // log.warn("GEOJSON-DEBUG [{}] n={}: {}", new Object[]{phase, route.size(), sb});
        System.out.println("pupa");
    }

    /** Realna geometria: pojedynczy LineString (np. finalna trasa po BRouterze). */
    void geometry(String phase, List<double[]> geometry) {
        geometry(phase, geometry, null, -1);
    }

    void geometry(String phase, List<double[]> geometry, List<double[]> waypoints) {
        geometry(phase, geometry, waypoints, -1);
    }

    /** Realna geometria (LineString) + opcjonalnie ponumerowane waypointy NA tej trasie + linia ROUTE-STATS. */
    void geometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (!debugGeoJson || geometry == null || geometry.size() < 2) return;
        StringBuilder sb = new StringBuilder(geometry.size() * 24);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"phase\":\"")
                .append(phase).append("\",\"kind\":\"geometry\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        appendCoords(sb, geometry);
        sb.append("]}}");
        if (waypoints != null) {
            for (int i = 0; i < waypoints.size(); i++) {
                sb.append(",{\"type\":\"Feature\",\"properties\":{\"idx\":").append(i)
                        .append(",\"phase\":\"").append(phase).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
                appendPt(sb, waypoints.get(i));
                sb.append("]}}");
            }
        }
        sb.append("]}");
        System.out.println("dupa");
//        log.info("GEOJSON-DEBUG [{}] pts={} wps={}: {}",
//                new Object[]{phase, geometry.size(), waypoints == null ? 0 : waypoints.size(), sb});
        stats(phase, geometry, waypoints, realKm);
        if (waypoints != null) skeleton(phase, waypoints); // RUNDA 24: skeleton ZAWSZE obok geometry (życzenie usera)
        System.out.println("pupa");
    }

    /** Zwięzła linia metryk trasy dla fazy: dystans/wznios/effort vs budżet + gminy per kategoria + diagnostyka wp. */
    private void stats(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (geometry == null || geometry.size() < 2) return;
        int wps = waypoints == null ? 0 : waypoints.size();
        double km = realKm; // autorytatywny (BRouter/cache) — spójny z effortem algorytmu
        if (km <= 0) {       // fallback: haversine z geometrii (gdy brak realKm)
            km = 0;
            for (int i = 0; i < geometry.size() - 1; i++) {
                km += velomarker.service.planning.WaypointSelector.haversineKm(geometry.get(i), geometry.get(i + 1));
            }
        }
        double climb = metrics.climbM(geometry);
        double effort = km + params.alphaKmPerMeter() * climb;
        String budgetStr = budget > 0
                ? Math.round(effort) + "/" + Math.round(budget) + " (" + Math.round(effort * 100.0 / budget) + "%)"
                : "n/a";
        String areasStr = "n/a";
        int total = 0, deep = 0;
        String noDeepStr = "";
        if (gminaIndex != null) {
            Map<Integer, Integer> visits = SeedBuilder.countVisitsPerArea(geometry, gminaIndex);
            total = visits.size(); // pełny wielokąt (findGminaForPoint) — DOTKNIĘTE, w tym muśnięte (<200m)
            Map<String, Integer> byCat = new java.util.TreeMap<>();
            for (Integer id : visits.keySet()) {
                byCat.merge(areaCat != null ? areaCat.getOrDefault(id, "?") : "?", 1, Integer::sum);
            }
            areasStr = byCat.toString();
            Set<Integer> deepCov = gminaIndex.visitedAreaIds(geometry);
            deep = deepCov.size();
            if (waypoints != null) {
                Set<Integer> wpDeep = new HashSet<>();
                for (double[] wp : waypoints) {
                    UnvisitedArea a = gminaIndex.findCreditedGminaForPoint(wp[0], wp[1]);
                    if (a != null) wpDeep.add(a.areaId());
                }
                Map<Integer, String> idName = new java.util.HashMap<>();
                for (double[] p : geometry) {
                    UnvisitedArea a = gminaIndex.findGminaForPoint(p[0], p[1]);
                    if (a != null) idName.putIfAbsent(a.areaId(), a.name());
                }
                List<String> noDeep = new ArrayList<>();
                for (int gid : deepCov) if (!wpDeep.contains(gid)) noDeep.add(idName.getOrDefault(gid, "id" + gid));
                noDeep.sort(null);
                noDeepStr = " | bez-głębokiego-wp(≥200m, " + noDeep.size() + "): " + noDeep;
            }
        }
        // gminy z >1 wp (łamanie inwariantu 1 wp/gmina)
        String dupStr = "n/a";
        if (waypoints != null && gminaIndex != null) {
            Map<Integer, Integer> wpPerArea = new java.util.HashMap<>();
            Map<Integer, String> areaName = new java.util.HashMap<>();
            for (double[] wp : waypoints) {
                UnvisitedArea a = gminaIndex.findGminaForPoint(wp[0], wp[1]);
                if (a == null) continue;
                wpPerArea.merge(a.areaId(), 1, Integer::sum);
                areaName.putIfAbsent(a.areaId(), a.name());
            }
            List<String> dups = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : wpPerArea.entrySet())
                if (e.getValue() > 1) dups.add(areaName.get(e.getKey()) + "(" + e.getValue() + ")");
            dups.sort(null);
            dupStr = dups.size() + " " + dups;
        }
        log.info("ROUTE-STATS [{}]: dist={} km, climb={} m, effort={} budżetu, wps={}, gminy={} (≥200m: {}) {} | dup-wp={}{}",
                new Object[]{phase, Math.round(km), Math.round(climb), budgetStr, wps, total, deep, areasStr, dupStr, noDeepStr});
    }

    private static void appendCoords(StringBuilder sb, List<double[]> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            appendPt(sb, pts.get(i));
            sb.append(']');
        }
    }

    private static void appendPt(StringBuilder sb, double[] p) {
        sb.append(String.format(java.util.Locale.ROOT, "%.6f,%.6f", p[0], p[1]));
    }
}
