package velomarker.service.planning.coverage;

import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Miary trasy: effort (km + alpha·wznios), dystans, wznios, sklejona geometria, zaliczone gminy.
 * Jedna odpowiedzialność: „policz metryki trasy". Owija {@link EdgeRouter} (geometria/dystans per leg)
 * + elevation (wznios) + {@link GminaIndex} (pokrycie). Instancja per plan.
 */
final class RouteMetrics {

    /** Wznios liczony oknami ≤ tylu punktów (cap sample BRoutera zaniżałby climb długich tras). */
    private static final int CLIMB_WINDOW_PTS = 400;

    private final EdgeRouter edges;
    private final GminaIndex gminaIndex;
    private final ElevationDataSource elevation;
    private final double alpha;

    RouteMetrics(EdgeRouter edges, GminaIndex gminaIndex, ElevationDataSource elevation, double alpha) {
        this.edges = edges;
        this.gminaIndex = gminaIndex;
        this.elevation = elevation;
        this.alpha = alpha;
    }

    /** Wynik ewaluacji trasy: effort, zaliczone gminy, sklejona geometria. */
    record EvalResult(double effort, Set<Integer> visited, List<double[]> geometry) {}

    /** Effort per-edge z cache (Σ edge.effort). Pierwsza ewaluacja pre-warmuje wszystkie krawędzie. */
    double effortViaCache(List<double[]> route) {
        edges.prewarm(route);
        double e = 0;
        for (int i = 0; i < route.size() - 1; i++) e += edges.edge(route.get(i), route.get(i + 1)).effort();
        return e;
    }

    /** Effort DOKŁADNY = realKm + alpha·wznios(CAŁA sklejona geometria) — formuła ROUTE-STATS. 0 BRouter. */
    double effortAccurate(List<double[]> route) {
        return realKm(route) + alpha * climbM(realGeometry(route));
    }

    /** Realny dystans trasy = Σ EdgeInfo.distanceKm (cache-hity). */
    double realKm(List<double[]> route) {
        double km = 0;
        for (int i = 0; i < route.size() - 1; i++) km += edges.edge(route.get(i), route.get(i + 1)).distanceKm();
        return km;
    }

    /** TANI Σ haversine kolejnych wierzchołków (bez BRoutera) — baza estymatora effortu. */
    double haversineKm(List<double[]> route) {
        double hav = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            hav += velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
        }
        return hav;
    }

    /** Sumaryczny wznios liczony oknami (≤{@value #CLIMB_WINDOW_PTS} pkt) — bez zaniżania na długich trasach. */
    double climbM(List<double[]> coords) {
        if (elevation == null || coords == null || coords.size() < 2) return 0;
        double total = 0;
        for (int i = 0; i < coords.size() - 1; i += CLIMB_WINDOW_PTS) {
            int end = Math.min(coords.size(), i + CLIMB_WINDOW_PTS + 1); // +1 overlap dla ciągłości okien
            try {
                List<double[]> window = coords.subList(i, end);
                total += elevation.sample(window, window.size()).gainM();
            } catch (RuntimeException ignored) { /* brak DEM dla okna → 0 */ }
        }
        return total;
    }

    /** Sklej realną geometrię całej trasy z per-leg geometrii cache (bez duplikowania styków legów). */
    List<double[]> realGeometry(List<double[]> route) {
        List<double[]> geom = new ArrayList<>();
        for (int i = 0; i < route.size() - 1; i++) {
            EdgeCache.EdgeInfo info = edges.edge(route.get(i), route.get(i + 1));
            List<double[]> seg = info.geometry();
            if (seg == null || seg.isEmpty()) continue;
            // crosspoint-injection: na styku wstaw snap(route[i]) = crosspointA(edge_i) PRZED segmentem — geometria
            // BRoutera (track.nodes) sam crosspoint pomija, a deeplyVisitedAreaIds ma liczyć pokrycie na realnym snapie wp.
            // crosspointA (START edge'a, =getMatchedWaypoint(0)) to JEDYNE pewne źródło snapu w btools (koniec=null).
            double[] cp = info.crosspointA();
            if (cp != null && !geom.isEmpty()
                    && velomarker.service.planning.WaypointSelector.haversineKm(geom.get(geom.size() - 1), cp) > 0.002) {
                geom.add(cp.clone());
            }
            int from = geom.isEmpty() ? 0 : 1;
            for (int j = from; j < seg.size(); j++) geom.add(seg.get(j));
        }
        return geom;
    }

    /** Ewaluacja całej trasy: effort + zaliczone gminy (na sklejonej geometrii) + geometria. */
    EvalResult eval(List<double[]> route) {
        edges.prewarm(route);
        List<double[]> geom = new ArrayList<>();
        double effort = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            EdgeCache.EdgeInfo info = edges.edge(route.get(i), route.get(i + 1));
            effort += info.effort();
            List<double[]> eg = info.geometry();
            int from = geom.isEmpty() ? 0 : 1;
            for (int k = from; k < eg.size(); k++) geom.add(eg.get(k));
        }
        return new EvalResult(effort, gminaIndex.visitedAreaIds(geom), geom);
    }
}
