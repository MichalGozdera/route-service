package velomarker.service.planning.coverage;

import velomarker.service.planning.WaypointSelector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skalowalny local-search dla Coverage: 2-opt + or-opt na liście k-NAJBLIŻSZYCH GEOGRAFICZNIE sąsiadów
 * (grid-bucket), z DON'T-LOOK-BITS. Optymalizuje haversine (proxy effortu — BRouter per ruch byłby za drogi).
 *
 * <p>Dlaczego nie okno-kolejność: trasa Hilberta ma skoki na granicach komórek krzywej → pary blisko
 * GEOGRAFICZNIE bywają daleko w kolejności (= długie nawroty), których okno ±k w kolejności nie widzi.
 * k-nearest patrzy po GEOGRAFII (siatka), więc łapie nawroty niezależnie od odległości w kolejności.
 *
 * <p>Skalowanie (2500 PL … 35k FR): grid build O(n); don't-look tnie skanowanie do garstki aktywnych wp;
 * or-opt przenosi mały segment (move, NIE reverse) → tani niezależnie od długości nawrotu. Anchory
 * (route[0] start, route[n-1] end) NIGDY nie ruszane.
 */
public final class CoverageLocalSearch {

    private CoverageLocalSearch() {
    }

    /** Liczba PRZEBIEGÓW-na-wp (cap anty-cykl: MAX_ITER×n ruchów). Zbieżność i tak przez pustą kolejkę don't-look. */
    private static final int MAX_ITER = 300;
    /** Minimalna poprawa (km), by zaakceptować ruch — anty-mikroswap/szum. */
    private static final double IMPROVE_EPSILON = 0.01;
    /** Ilu najbliższych geograficznie kandydatów rozważa 2-opt/or-opt (pasmo 10-15). */
    private static final int K_NEAREST = 12;
    /** Maksymalna długość przenoszonego segmentu w or-opt (1-3 wp). */
    private static final int MAX_OR_OPT_SEG = 3;

    /**
     * Indeks przestrzenny (grid-bucket) + prekompute k-najbliższych geograficznie. Pozycje wp stałe w
     * obrębie jednego {@link #optimize} (zmienia się tylko KOLEJNOŚĆ w route, nie współrzędne).
     */
    static final class GeoNeighbors {
        final double[][] owner;                              // build-index → obiekt wp
        final int[][] knn;                                   // build-index → K najbliższych build-index
        final IdentityHashMap<double[], Integer> buildIdx;   // wp → build-index (identity!)

        private GeoNeighbors(double[][] owner, int[][] knn, IdentityHashMap<double[], Integer> buildIdx) {
            this.owner = owner;
            this.knn = knn;
            this.buildIdx = buildIdx;
        }

        static GeoNeighbors of(List<double[]> route, int k) {
            int n = route.size();
            double[][] owner = new double[n][];
            IdentityHashMap<double[], Integer> buildIdx = new IdentityHashMap<>(n * 2);
            double minLng = Double.MAX_VALUE, minLat = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double[] p = route.get(i);
                owner[i] = p;
                buildIdx.put(p, i);
                minLng = Math.min(minLng, p[0]);
                maxLng = Math.max(maxLng, p[0]);
                minLat = Math.min(minLat, p[1]);
                maxLat = Math.max(maxLat, p[1]);
            }
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
            double cellW = Math.max(1e-9, maxLng - minLng) / cols;
            double cellH = Math.max(1e-9, maxLat - minLat) / cols;
            int[] cx = new int[n], cy = new int[n];
            Map<Integer, List<Integer>> buckets = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                int gx = (int) Math.min(cols - 1, Math.max(0, (owner[i][0] - minLng) / cellW));
                int gy = (int) Math.min(cols - 1, Math.max(0, (owner[i][1] - minLat) / cellH));
                cx[i] = gx;
                cy[i] = gy;
                buckets.computeIfAbsent(gy * cols + gx, kk -> new ArrayList<>()).add(i);
            }
            int[][] knn = new int[n][];
            for (int i = 0; i < n; i++) {
                knn[i] = kNearest(i, owner, cx[i], cy[i], cols, buckets, k);
            }
            return new GeoNeighbors(owner, knn, buildIdx);
        }

        /** K najbliższych geograficznie do build-index {@code i} — skan komórki + rosnących pierścieni (+1 zapasu). */
        private static int[] kNearest(int i, double[][] owner, int gx, int gy, int cols,
                                      Map<Integer, List<Integer>> buckets, int k) {
            List<Integer> cand = new ArrayList<>();
            int collectedRing = -1;
            for (int ring = 0; ring <= cols; ring++) {
                addRing(cand, i, gx, gy, ring, cols, buckets);
                if (cand.size() >= k && collectedRing < 0) collectedRing = ring; // pierwszy pierścień z ≥k
                if (collectedRing >= 0 && ring >= collectedRing + 1) break;       // +1 pierścień zapasu (granice komórek)
            }
            double[] me = owner[i];
            cand.sort(Comparator.comparingDouble(c -> WaypointSelector.haversineKm(me, owner[c])));
            int m = Math.min(k, cand.size());
            int[] res = new int[m];
            for (int t = 0; t < m; t++) res[t] = cand.get(t);
            return res;
        }

        private static void addRing(List<Integer> cand, int self, int gx, int gy, int ring, int cols,
                                    Map<Integer, List<Integer>> buckets) {
            if (ring == 0) {
                addCell(cand, self, gx, gy, cols, buckets);
                return;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) continue; // tylko obwód pierścienia
                    addCell(cand, self, gx + dx, gy + dy, cols, buckets);
                }
            }
        }

        private static void addCell(List<Integer> cand, int self, int gx, int gy, int cols,
                                    Map<Integer, List<Integer>> buckets) {
            if (gx < 0 || gy < 0 || gx >= cols || gy >= cols) return;
            List<Integer> b = buckets.get(gy * cols + gx);
            if (b == null) return;
            for (int idx : b) if (idx != self) cand.add(idx);
        }
    }

    /** Rozplącz {@code route} (2-opt + or-opt, k-nearest, don't-look). Buduje indeks sąsiedztwa raz. Zwraca # ruchów. */
    public static int optimize(List<double[]> route) {
        if (route.size() < 4) return 0;
        return optimize(route, GeoNeighbors.of(route, K_NEAREST));
    }

    /** Jak {@link #optimize(List)}, ale z gotowym sąsiedztwem (build RAZ przez callera, np. refineOrder). */
    static int optimize(List<double[]> route, GeoNeighbors nb) {
        int n = route.size();
        if (n < 4) return 0;
        IdentityHashMap<double[], Integer> pos = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) pos.put(route.get(i), i);
        ArrayDeque<double[]> queue = new ArrayDeque<>(n);
        Set<double[]> inQueue = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 1; i < n - 1; i++) {                    // anchory (0, n-1) poza kolejką
            double[] v = route.get(i);
            queue.add(v);
            inQueue.add(v);
        }
        int moves = 0;
        long cap = (long) MAX_ITER * n;
        while (!queue.isEmpty() && moves < cap) {
            double[] v = queue.poll();
            inQueue.remove(v);
            Integer ip = pos.get(v);
            if (ip == null) continue;
            int i = ip;
            if (i < 1 || i > n - 2) continue;
            boolean moved = try2opt(route, nb, pos, queue, inQueue, i)
                    || tryOrOpt(route, nb, pos, queue, inQueue, i);
            if (moved) moves++;                              // !moved → v zostaje poza kolejką (don't-look)
        }
        return moves;
    }

    private static int[] neigh(GeoNeighbors nb, double[] wp) {
        Integer bi = nb.buildIdx.get(wp);
        return bi == null ? null : nb.knn[bi];
    }

    private static void enqueue(double[] v, ArrayDeque<double[]> queue, Set<double[]> inQueue) {
        if (v != null && inQueue.add(v)) queue.add(v);       // add zwraca false gdy już w secie
    }

    /** 2-opt: dla krawędzi (i,i+1) próbuj zamknąć z krawędzią przy geograficznie bliskim wp (reverse). */
    private static boolean try2opt(List<double[]> route, GeoNeighbors nb, IdentityHashMap<double[], Integer> pos,
                                   ArrayDeque<double[]> queue, Set<double[]> inQueue, int i) {
        int n = route.size();
        double[] a = route.get(i), b = route.get(i + 1);
        for (int pass = 0; pass < 2; pass++) {
            int[] cands = neigh(nb, pass == 0 ? a : b);      // kandydaci z knn(a) ∪ knn(b)
            if (cands == null) continue;
            for (int cbi : cands) {
                Integer jp = pos.get(nb.owner[cbi]);
                if (jp == null) continue;
                int lo = Math.min(i, jp), hi = Math.max(i, jp);
                if (lo < 0 || lo > n - 3 || hi < lo + 2 || hi > n - 2) continue; // anchory nietknięte
                double[] alo = route.get(lo), blo = route.get(lo + 1), chi = route.get(hi), dhi = route.get(hi + 1);
                double oldC = WaypointSelector.haversineKm(alo, blo) + WaypointSelector.haversineKm(chi, dhi);
                double newC = WaypointSelector.haversineKm(alo, chi) + WaypointSelector.haversineKm(blo, dhi);
                if (newC < oldC - IMPROVE_EPSILON) {
                    Collections.reverse(route.subList(lo + 1, hi + 1));
                    for (int t = lo + 1; t <= hi; t++) pos.put(route.get(t), t);
                    enqueue(alo, queue, inQueue);
                    enqueue(route.get(lo + 1), queue, inQueue);
                    enqueue(route.get(hi), queue, inQueue);
                    enqueue(dhi, queue, inQueue);
                    return true;
                }
            }
        }
        return false;
    }

    /** or-opt: wyjmij segment [i..i+L-1] (L≤3) i wstaw obok geograficznie bliskiego wp, jeśli skraca (MOVE, nie reverse). */
    private static boolean tryOrOpt(List<double[]> route, GeoNeighbors nb, IdentityHashMap<double[], Integer> pos,
                                    ArrayDeque<double[]> queue, Set<double[]> inQueue, int i) {
        int n = route.size();
        for (int L = 1; L <= MAX_OR_OPT_SEG; L++) {
            int segEnd = i + L - 1;
            if (segEnd > n - 2) break;                       // segment przed end-anchorem
            double[] prev = route.get(i - 1), seg0 = route.get(i), segL = route.get(segEnd), next = route.get(segEnd + 1);
            double removed = WaypointSelector.haversineKm(prev, seg0) + WaypointSelector.haversineKm(segL, next)
                    - WaypointSelector.haversineKm(prev, next);
            int[] cands = neigh(nb, seg0);
            if (cands == null) continue;
            for (int cbi : cands) {
                Integer jp = pos.get(nb.owner[cbi]);
                if (jp == null) continue;
                int j = jp;
                if (j >= i - 1 && j <= segEnd) continue;     // wewnątrz/sąsiad segmentu (brak ruchu)
                if (j < 0 || j > n - 2) continue;            // route[j+1] musi istnieć (i ≠ end-anchor edge)
                double[] aj = route.get(j), bj = route.get(j + 1);
                double added = WaypointSelector.haversineKm(aj, seg0) + WaypointSelector.haversineKm(segL, bj)
                        - WaypointSelector.haversineKm(aj, bj);
                if (added - removed < -IMPROVE_EPSILON) {
                    moveSegment(route, pos, i, L, j);
                    enqueue(prev, queue, inQueue);
                    enqueue(next, queue, inQueue);
                    enqueue(aj, queue, inQueue);
                    enqueue(bj, queue, inQueue);
                    enqueue(seg0, queue, inQueue);
                    return true;
                }
            }
        }
        return false;
    }

    /** Przenieś segment {@code [i..i+L-1]} tak, by trafił PO oryginalnej pozycji {@code insertJ}; zaktualizuj pos. */
    private static void moveSegment(List<double[]> route, IdentityHashMap<double[], Integer> pos, int i, int L, int insertJ) {
        List<double[]> seg = new ArrayList<>(route.subList(i, i + L));
        route.subList(i, i + L).clear();
        int insertAt = insertJ < i ? insertJ + 1 : insertJ + 1 - L;   // korekta o L, gdy insertJ był ZA segmentem
        route.addAll(insertAt, seg);
        int lo = Math.min(i, insertAt);
        int hi = Math.min(route.size() - 1, Math.max(i, insertAt) + L);  // +L pokrywa wstawiony segment
        for (int t = lo; t <= hi; t++) pos.put(route.get(t), t);
    }
}
