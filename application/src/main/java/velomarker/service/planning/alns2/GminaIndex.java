package velomarker.service.planning.alns2;

import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.service.planning.SpatialGrid;
import velomarker.service.planning.WaypointSelector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pomocnik planowania nad pulą nieodwiedzonych obszarów. Dwie odpowiedzialności:
 *
 * <ol>
 *   <li><b>Coverage</b> (zaliczenia) — deleguje do {@link AreaCoverageIndex} (JTS w adapterze, PEŁNA
 *       geometria, plain intersect jak front). Tu już bez ręcznego ray-castingu / strict-depth —
 *       to było kompensacją coarse 48-pkt ringów, które już nie istnieją.</li>
 *   <li><b>Heurystyki seeda</b> (czysta geometria, bez JTS): {@link #samplePointsFor} (entry-pointy
 *       przesunięte 500m w głąb, by BRouter wjechał), {@link #distToRoute},
 *       {@link #avgNearestNeighborDistKm}.</li>
 * </ol>
 */
public class GminaIndex {

    /** K najbliższych sąsiadów (po centroidach) do testu „otoczona" oraz minimalny ułamek świeżo
     *  zaliczonych sąsiadów, by uznać obszar za dziurę WEWNĘTRZNĄ (a nie peryferyjną obwódkę). */
    private static final int HOLE_KNN = 8;

    private final List<UnvisitedArea> allAreas;
    private final AreaCoverageIndex coverage;
    private final Map<Integer, double[][]> samplePointsCache = new HashMap<>();
    private final int samplePointsPerGmina;
    /** areaId → id-ki HOLE_KNN najbliższych obszarów (po centroidzie). Leniwie liczone raz (O(n²)). */
    private Map<Integer, int[]> kNearestCache;

    public GminaIndex(List<UnvisitedArea> areas, int samplePointsPerGmina, AreaCoverageIndex coverage) {
        this.allAreas = new ArrayList<>(areas);
        this.samplePointsPerGmina = Math.max(1, samplePointsPerGmina);
        this.coverage = coverage;
    }

    /** Najmniejszy powierzchniowo obszar zawierający punkt (obwarzanek: miasto w dziurze wiejskiej),
     *  lub null. Deleguje do JTS coverage (pełna geometria, contains z dziurami). */
    public UnvisitedArea findGminaForPoint(double lng, double lat) {
        return coverage.findAreaForPoint(lng, lat);
    }

    /**
     * Id obszarów zaliczonych przez trasę — PLAIN intersect na pełnej geometrii (jak front turf),
     * deleguje do {@link AreaCoverageIndex}. Jednolite kryterium: seed/densify/raport.
     */
    public Set<Integer> visitedAreaIds(List<double[]> routeGeometry) {
        return coverage.visitedAreaIds(routeGeometry);
    }

    /**
     * N **ENTRY-POINTS** dla area: ring vertices przesunięte ~500m w kierunku centroidu.
     * Cel: trasa BRouter dotyka KRAWĘDZI gminy i wjeżdża w głąb (wystarczy żeby zaliczyć), NIE jedzie
     * do centrum. To JEDYNE miejsce gdzie „głębokość wjazdu" ma znaczenie — wymusza na BRouterze realny
     * wjazd, by gotowa trasa przecięła gminę (a nie ślizgała się po granicy).
     *
     * <p>Algorytm per sample: weź co (ring.length / N) vertex, oblicz wektor vertex→centroid,
     * znormalizuj, przesuń vertex o ~500m w tym kierunku.
     */
    public double[][] samplePointsFor(UnvisitedArea area) {
        return samplePointsCache.computeIfAbsent(area.areaId(), id -> {
            double[][] ring = area.ring();
            if (ring == null || ring.length == 0) {
                return new double[][]{{area.lng(), area.lat()}};
            }
            int n = Math.min(samplePointsPerGmina, ring.length);
            double[][] pts = new double[n][];
            int step = Math.max(1, ring.length / n);
            double offsetDeg = 0.0045; // ~500m w kierunku centroidu (BRouter czasem ślizga się po granicy)
            double cLng = area.lng();
            double cLat = area.lat();
            for (int i = 0; i < n; i++) {
                int idx = Math.min(ring.length - 1, i * step);
                double vLng = ring[idx][0];
                double vLat = ring[idx][1];
                double dirLng = cLng - vLng;
                double dirLat = cLat - vLat;
                double len = Math.sqrt(dirLng * dirLng + dirLat * dirLat);
                if (len < 1e-9) {
                    pts[i] = new double[]{vLng, vLat};
                } else {
                    double scale = Math.min(offsetDeg / len, 0.5);
                    pts[i] = new double[]{vLng + dirLng * scale, vLat + dirLat * scale};
                }
            }
            return pts;
        });
    }

    /**
     * Minimum dist (km) od area (najbliższy z sample points) do najbliższego segmentu route.
     */
    public double distToRoute(UnvisitedArea area, List<double[]> route) {
        double[][] samples = samplePointsFor(area);
        double minDist = Double.MAX_VALUE;
        for (double[] p : samples) {
            for (int i = 0; i < route.size() - 1; i++) {
                double[] a = route.get(i);
                double[] b = route.get(i + 1);
                double d = pointToSegmentKm(p[0], p[1], a[0], a[1], b[0], b[1]);
                if (d < minDist) minDist = d;
                if (minDist < 0.5) return minDist; // wystarczy "blisko"
            }
        }
        return minDist;
    }

    /** Punkt do segmentu haversine — copy z SpatialAreaIndex. */
    private static double pointToSegmentKm(double px, double py,
                                            double ax, double ay,
                                            double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t;
        if (len2 < 1e-12) t = 0;
        else {
            t = ((px - ax) * dx + (py - ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
        }
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        return WaypointSelector.haversineKm(new double[]{px, py}, new double[]{projX, projY});
    }

    /**
     * Dziura WEWNĘTRZNA = obszar otoczony świeżo zaliczonymi, NIE peryferyjna obwódka przy trasie.
     * Kryterium: ≥ {@code minFraction} z {@link #HOLE_KNN} najbliższych sąsiadów (po centroidach z puli)
     * jest w zbiorze {@code visited} (świeżo zaliczone). Peryferyjna gmina ma sąsiadów w otwartym
     * nieodwiedzonym terenie → niski ułamek → odrzucona. Odległość-do-trasy tego nie odróżnia
     * (skrajna i wewnętrzna mogą być tak samo blisko linii).
     */
    public boolean isEnclosedHole(UnvisitedArea a, Set<Integer> visited, double minFraction) {
        return enclosedFraction(a.areaId(), visited) >= minFraction;
    }

    /** Ułamek z {@link #HOLE_KNN} najbliższych sąsiadów (po centroidzie), który jest świeżo zaliczony. */
    public double enclosedFraction(int areaId, Set<Integer> visited) {
        int[] nn = kNearest().get(areaId);
        if (nn == null || nn.length == 0) return 0;
        int hit = 0;
        for (int id : nn) {
            if (visited.contains(id)) hit++;
        }
        return (double) hit / nn.length;
    }

    /** Leniwa pre-kalkulacja HOLE_KNN najbliższych po centroidach (siatka spatial, O(n) zamiast O(n²)). */
    private Map<Integer, int[]> kNearest() {
        if (kNearestCache != null) return kNearestCache;
        int n = allAreas.size();
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) pts[i] = new double[]{allAreas.get(i).lng(), allAreas.get(i).lat()};
        SpatialGrid grid = new SpatialGrid(pts);
        int k = Math.min(HOLE_KNN, Math.max(0, n - 1));
        Map<Integer, int[]> result = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            int[] nnIdx = grid.kNearestIndices(i, k);
            int[] ids = new int[nnIdx.length];
            for (int t = 0; t < nnIdx.length; t++) ids[t] = allAreas.get(nnIdx[t]).areaId();
            result.put(allAreas.get(i).areaId(), ids);
        }
        kNearestCache = result;
        return result;
    }

    /**
     * Średnia odległość do najbliższego sąsiada (centroid-to-centroid) wśród obszarów danej kategorii
     * w PULI. Gminy gęste (NN ~5 km) → niski reward; kreissitz rzadkie (NN ~30 km) → wysoki. Liczone na
     * WYSELEKCJONOWANYCH (bbox pool), bo gęstość zależy od regionu.
     *
     * @param categoryAreas obszary jednej kategorii
     * @return średni dystans NN w km (lub 0 gdy < 2 obszary)
     */
    public static double avgNearestNeighborDistKm(List<UnvisitedArea> categoryAreas) {
        int n = categoryAreas.size();
        if (n < 2) return 0;
        // Siatka spatial: średni NN w ~O(n) zamiast O(n²) — kluczowe dla dużych puli (cały kraj).
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) {
            UnvisitedArea a = categoryAreas.get(i);
            pts[i] = new double[]{a.lng(), a.lat()};
        }
        SpatialGrid grid = new SpatialGrid(pts);
        double sumNN = 0;
        for (int i = 0; i < n; i++) {
            double d = grid.nearestDistKm(i);
            if (d < Double.MAX_VALUE) sumNN += d;
        }
        return sumNN / n;
    }

    public List<UnvisitedArea> allAreas() { return allAreas; }
    public int size() { return allAreas.size(); }
}
