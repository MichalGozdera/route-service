package velomarker.service.planning;

import java.util.ArrayList;
import java.util.List;

/**
 * Lekka jednorodna siatka przestrzenna nad punktami {@code [lng,lat]} do zapytań sąsiedztwa w ~O(1) na
 * punkt (czyli O(n) zamiast O(n²) dla całego zbioru). Zastępuje trzy pętle „każdy z każdym":
 * <ul>
 *   <li>reward — najbliższy sąsiad per kategoria ({@code nearestDistKm}),</li>
 *   <li>enclosed-hole — k najbliższych ({@code kNearestIndices}),</li>
 *   <li>density-aware sort — liczba sąsiadów w promieniu ({@code countWithinKm}).</li>
 * </ul>
 *
 * <p>Dystanse liczone haversine ({@link WaypointSelector}); komórki w stopniach. {@code nearestDistKm}
 * jest dokładny (ekspandujący pierścień z gwarancją dolnej granicy), {@code kNearestIndices} przybliżony
 * (wystarczający dla heurystyki „otoczona"). Sąsiedztwo graniczne: punkt przy granicy państwa ma sąsiadów
 * tylko w jedną stronę — siatka zwraca po prostu istniejących, brak strony granicy nie jest karą.
 */
public final class SpatialGrid {

    private static final int[] EMPTY = new int[0];

    private final double[][] pts;       // [i] = {lng, lat}
    private final double cellDeg;
    private final double minLng, minLat;
    private final int cols, rows;
    private final int[][] cells;        // [row*cols + col] -> indeksy punktów
    private final double cellKmMin;     // konserwatywna (najmniejsza) liczba km na 1 komórkę

    public SpatialGrid(double[][] pts) {
        this.pts = pts;
        int n = pts.length;
        double mnLng = Double.MAX_VALUE, mnLat = Double.MAX_VALUE;
        double mxLng = -Double.MAX_VALUE, mxLat = -Double.MAX_VALUE;
        for (double[] p : pts) {
            if (p[0] < mnLng) mnLng = p[0];
            if (p[0] > mxLng) mxLng = p[0];
            if (p[1] < mnLat) mnLat = p[1];
            if (p[1] > mxLat) mxLat = p[1];
        }
        if (n == 0) { mnLng = mnLat = 0; mxLng = mxLat = 0; }
        this.minLng = mnLng;
        this.minLat = mnLat;
        double spanLng = Math.max(1e-6, mxLng - mnLng);
        double spanLat = Math.max(1e-6, mxLat - mnLat);
        // celuj w ~2 punkty/komórkę
        double cell = Math.sqrt((spanLng * spanLat) / Math.max(1.0, n / 2.0));
        this.cellDeg = Math.min(2.0, Math.max(0.01, cell));
        this.cols = (int) (spanLng / cellDeg) + 1;
        this.rows = (int) (spanLat / cellDeg) + 1;
        double maxAbsLat = Math.max(Math.abs(mnLat), Math.abs(mxLat));
        this.cellKmMin = cellDeg * 111.0 * Math.max(0.1, Math.cos(Math.toRadians(maxAbsLat)));

        List<List<Integer>> tmp = new ArrayList<>(cols * rows);
        for (int c = 0; c < cols * rows; c++) tmp.add(null);
        for (int i = 0; i < n; i++) {
            int ci = cellOf(pts[i]);
            List<Integer> lst = tmp.get(ci);
            if (lst == null) { lst = new ArrayList<>(); tmp.set(ci, lst); }
            lst.add(i);
        }
        this.cells = new int[cols * rows][];
        for (int c = 0; c < cols * rows; c++) {
            List<Integer> lst = tmp.get(c);
            if (lst == null) { cells[c] = EMPTY; continue; }
            int[] arr = new int[lst.size()];
            for (int t = 0; t < arr.length; t++) arr[t] = lst.get(t);
            cells[c] = arr;
        }
    }

    private int colOf(double lng) {
        int c = (int) ((lng - minLng) / cellDeg);
        return Math.max(0, Math.min(cols - 1, c));
    }

    private int rowOf(double lat) {
        int r = (int) ((lat - minLat) / cellDeg);
        return Math.max(0, Math.min(rows - 1, r));
    }

    private int cellOf(double[] p) { return rowOf(p[1]) * cols + colOf(p[0]); }

    /** Odległość (km) do najbliższego innego punktu względem {@code i}; Double.MAX_VALUE gdy brak. */
    public double nearestDistKm(int i) {
        int cc = colOf(pts[i][0]), cr = rowOf(pts[i][1]);
        double best = Double.MAX_VALUE;
        int maxR = cols + rows;
        for (int r = 0; r <= maxR; r++) {
            for (int dc = -r; dc <= r; dc++) {
                for (int dr = -r; dr <= r; dr++) {
                    if (Math.max(Math.abs(dc), Math.abs(dr)) != r) continue; // tylko obrzeże pierścienia r
                    int c = cc + dc, rr = cr + dr;
                    if (c < 0 || c >= cols || rr < 0 || rr >= rows) continue;
                    for (int j : cells[rr * cols + c]) {
                        if (j == i) continue;
                        double d = WaypointSelector.haversineKm(pts[i], pts[j]);
                        if (d < best) best = d;
                    }
                }
            }
            if (best <= r * cellKmMin) break; // żaden punkt z dalszych pierścieni nie będzie bliżej
        }
        return best;
    }

    /** Indeks punktu w siatce najbliższego ZEWNĘTRZNEMU coord {@code (lng, lat)}. {@code -1} gdy siatka pusta. */
    public int nearestIndexTo(double lng, double lat) {
        if (pts.length == 0) return -1;
        int cc = colOf(lng), cr = rowOf(lat);
        int bestIdx = -1;
        double best = Double.MAX_VALUE;
        int maxR = cols + rows;
        double[] q = {lng, lat};
        for (int r = 0; r <= maxR; r++) {
            for (int dc = -r; dc <= r; dc++) {
                for (int dr = -r; dr <= r; dr++) {
                    if (Math.max(Math.abs(dc), Math.abs(dr)) != r) continue;
                    int c = cc + dc, rr = cr + dr;
                    if (c < 0 || c >= cols || rr < 0 || rr >= rows) continue;
                    for (int j : cells[rr * cols + c]) {
                        double d = WaypointSelector.haversineKm(pts[j], q);
                        if (d < best) { best = d; bestIdx = j; }
                    }
                }
            }
            if (best <= r * cellKmMin) break;
        }
        return bestIdx;
    }

    /** Dystans (km) między punktem siatki i a zewnętrznymi coord {@code (lng, lat)}. Wykorzystywany po {@link #nearestIndexTo}. */
    public double distKmFromExternal(int i, double lng, double lat) {
        return WaypointSelector.haversineKm(pts[i], new double[]{lng, lat});
    }

    /** Liczba innych punktów w promieniu {@code radiusKm} od {@code i}. */
    public int countWithinKm(int i, double radiusKm) {
        int cc = colOf(pts[i][0]), cr = rowOf(pts[i][1]);
        int span = (int) Math.ceil(radiusKm / Math.max(1e-6, cellKmMin)) + 1;
        int count = 0;
        for (int dc = -span; dc <= span; dc++) {
            int c = cc + dc;
            if (c < 0 || c >= cols) continue;
            for (int dr = -span; dr <= span; dr++) {
                int rr = cr + dr;
                if (rr < 0 || rr >= rows) continue;
                for (int j : cells[rr * cols + c]) {
                    if (j == i) continue;
                    if (WaypointSelector.haversineKm(pts[i], pts[j]) < radiusKm) count++;
                }
            }
        }
        return count;
    }

    /** Indeksy do (max) {@code k} najbliższych punktów względem {@code i}, rosnąco dystansem (bez {@code i}). */
    public int[] kNearestIndices(int i, int k) {
        int n = pts.length;
        if (k <= 0 || n <= 1) return EMPTY;
        int cc = colOf(pts[i][0]), cr = rowOf(pts[i][1]);
        List<Integer> candIdx = new ArrayList<>();
        List<Double> candDist = new ArrayList<>();
        int maxR = cols + rows;
        int enoughAtRing = -1;
        for (int r = 0; r <= maxR; r++) {
            for (int dc = -r; dc <= r; dc++) {
                for (int dr = -r; dr <= r; dr++) {
                    if (Math.max(Math.abs(dc), Math.abs(dr)) != r) continue;
                    int c = cc + dc, rr = cr + dr;
                    if (c < 0 || c >= cols || rr < 0 || rr >= rows) continue;
                    for (int j : cells[rr * cols + c]) {
                        if (j == i) continue;
                        candIdx.add(j);
                        candDist.add(WaypointSelector.haversineKm(pts[i], pts[j]));
                    }
                }
            }
            if (candIdx.size() >= k) {
                if (enoughAtRing < 0) enoughAtRing = r;
                if (r >= enoughAtRing + 1) break; // +1 pierścień dla pewności
            }
        }
        Integer[] order = new Integer[candIdx.size()];
        for (int t = 0; t < order.length; t++) order[t] = t;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(candDist.get(x), candDist.get(y)));
        int m = Math.min(k, order.length);
        int[] res = new int[m];
        for (int t = 0; t < m; t++) res[t] = candIdx.get(order[t]);
        return res;
    }
}
