package velomarker.service.planning.coverage.debug;

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
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Renderery diagnostyczne seeda (GeoJSON, ROUTE-STATS, rollup strzałów BRoutera), włączane flagą debugGeoJson.
public final class CoverageDebug {

    private static final Logger log = LoggerFactory.getLogger(CoverageDebug.class);

    private final boolean debugGeoJson;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final CoverageAreaIndex coverageAreaIndex;
    private final CoveragePlannerParameters params;
    private final double budget;
    private final Map<Integer, String> areaCat;
    /** {@code areaId → nazwa rodzaju} (np. „Powiat"/„Bezirk") — do rozbicia live-podglądu na kategorie. */
    private final Map<Integer, String> areaRodzajName;
    /** Live-podgląd planowania (SSE) — emit aktualnej geometrii w każdym punkcie wołania geometry(). */
    private final velomarker.service.planning.PlanTraceSink traceSink;
    private Map<String, Long> shots = new java.util.HashMap<>();

    public CoverageDebug(boolean debugGeoJson, EdgeRouter edgeRouter, RouteMetrics metrics, CoverageAreaIndex coverageAreaIndex,
                  CoveragePlannerParameters params, double budget, Map<Integer, String> areaCat,
                  Map<Integer, String> areaRodzajName,
                  velomarker.service.planning.PlanTraceSink traceSink) {
        this.debugGeoJson = debugGeoJson;
        this.edgeRouter = edgeRouter;
        this.metrics = metrics;
        this.coverageAreaIndex = coverageAreaIndex;
        this.params = params;
        this.budget = budget;
        this.areaCat = areaCat;
        this.areaRodzajName = areaRodzajName != null ? areaRodzajName : Map.of();
        this.traceSink = traceSink != null ? traceSink : velomarker.service.planning.PlanTraceSink.NOOP;
    }

    public void resetShots() { shots = new java.util.HashMap<>(); }

    public void logShots(String phase) {
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

    public void skeleton(String phase, List<double[]> route) {
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
        System.out.println("pupa");
    }

    public void geometry(String phase, List<double[]> geometry) {
        geometry(phase, geometry, null, -1);
    }

    public void geometry(String phase, List<double[]> geometry, List<double[]> waypoints) {
        geometry(phase, geometry, waypoints, -1);
    }

    public void geometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        // Live-podgląd (SSE) leci ZAWSZE — niezależnie od debugGeoJson. To jedyny choke-point geometrii
        // w seedzie (baseline/co-N-batchy/anchor-iter/cut-runda/finalize-cykl). Dokładamy bieżące metryki:
        // km (realKm z cache), przewyższenie (climbM), zgarnięte obszary ≥200m (= kryterium finalnego kolorowania).
        if (geometry != null && geometry.size() >= 2) {
            double km = realKm > 0 ? realKm : haversineSum(geometry);
            int gain = (int) Math.round(metrics.climbM(geometry));
            List<Integer> covered = coverageAreaIndex != null
                    ? new ArrayList<>(coverageAreaIndex.visitedAreaIds(geometry)) : List.of();
            Map<String, Integer> byLevel = new java.util.TreeMap<>();
            for (int id : covered) byLevel.merge(areaRodzajName.getOrDefault(id, "?"), 1, Integer::sum);
            traceSink.emit(new velomarker.service.planning.PlanTraceFrame(phase, geometry, km, gain, covered, byLevel));
        }
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
        stats(phase, geometry, waypoints, realKm);
        if (waypoints != null) skeleton(phase, waypoints);
        System.out.println("pupa");
    }

    private void stats(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (geometry == null || geometry.size() < 2) return;
        int wps = waypoints == null ? 0 : waypoints.size();
        double km = realKm;
        if (km <= 0) {
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
        int total = 0, deep = 0, deeplyCovered = 0;
        Set<Integer> wpDeep220 = new HashSet<>();
        String noDeepStr = "";
        String shallowStr = "";
        if (coverageAreaIndex != null) {
            Map<Integer, Integer> visits = SeedBuilder.countVisitsPerArea(geometry, coverageAreaIndex);
            total = visits.size();
            Map<String, Integer> byCat = new java.util.TreeMap<>();
            for (Integer id : visits.keySet()) {
                byCat.merge(areaCat != null ? areaCat.getOrDefault(id, "?") : "?", 1, Integer::sum);
            }
            areasStr = byCat.toString();
            Set<Integer> deepCov = coverageAreaIndex.visitedAreaIds(geometry);
            deep = deepCov.size();
            Set<Integer> deeplyCov = coverageAreaIndex.deeplyVisitedAreaIds(geometry);
            deeplyCovered = deeplyCov.size();
            Map<Integer, String> idName = new java.util.HashMap<>();
            for (double[] p : geometry) {
                UnvisitedArea a = coverageAreaIndex.findGminaForPoint(p[0], p[1]);
                if (a != null) idName.putIfAbsent(a.areaId(), a.name());
            }
            List<String> shallow = new ArrayList<>();
            for (int gid : visits.keySet()) if (!deeplyCov.contains(gid)) shallow.add(idName.getOrDefault(gid, "id" + gid));
            shallow.sort(null);
            List<String> shallow200 = new ArrayList<>();
            for (int gid : visits.keySet()) if (!deepCov.contains(gid)) shallow200.add(idName.getOrDefault(gid, "id" + gid));
            shallow200.sort(null);
            shallowStr = " | ŚLAD<200m(" + shallow200.size() + "): " + (shallow200.size() < 50 ? shallow200 : "...")
                       + " | ślad<220m-zapas=" + shallow.size();
            if (waypoints != null) {
                Set<Integer> wpDeep = new HashSet<>();
                for (double[] wp : waypoints) {
                    UnvisitedArea a = coverageAreaIndex.findCreditedGminaForPoint(wp[0], wp[1]);
                    if (a != null) wpDeep.add(a.areaId());
                    UnvisitedArea a220 = coverageAreaIndex.findDeeplyCreditedGminaForPoint(wp[0], wp[1]);
                    if (a220 != null) wpDeep220.add(a220.areaId());
                }
                List<String> noDeep = new ArrayList<>();
                for (int gid : deepCov) if (!wpDeep.contains(gid)) noDeep.add(idName.getOrDefault(gid, "id" + gid));
                noDeep.sort(null);
                noDeepStr = " | bez-głębokiego-wp(≥200m, " + noDeep.size() + "): " + noDeep;
            }
        }
        String dupStr = "n/a";
        if (waypoints != null && coverageAreaIndex != null) {
            Map<Integer, Integer> wpPerArea = new java.util.HashMap<>();
            Map<Integer, String> areaName = new java.util.HashMap<>();
            for (double[] wp : waypoints) {
                UnvisitedArea a = coverageAreaIndex.findGminaForPoint(wp[0], wp[1]);
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
        log.info("ROUTE-STATS [{}]: dist={} km, climb={} m, effort={} budżetu, wps={}, gminy={} (≥200m: {}, ≥220m: {}) {} | dup-wp={} | wp≥220m={}{}{}",
                new Object[]{phase, Math.round(km), Math.round(climb), budgetStr, wps, total, deep, deeplyCovered, areasStr, dupStr, wpDeep220.size(), noDeepStr, shallowStr});
    }

    private static double haversineSum(List<double[]> geometry) {
        double km = 0;
        for (int i = 0; i < geometry.size() - 1; i++) {
            km += velomarker.service.planning.WaypointSelector.haversineKm(geometry.get(i), geometry.get(i + 1));
        }
        return km;
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
