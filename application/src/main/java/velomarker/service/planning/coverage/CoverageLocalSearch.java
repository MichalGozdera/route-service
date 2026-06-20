package velomarker.service.planning.coverage;

import velomarker.service.planning.WaypointSelector;

import java.util.Collections;
import java.util.List;

/**
 * Local search dla Coverage: 2-opt, relocate, swap. Wszystkie operatorów zachowują
 * route[0] (start) i route[route.size()-1] (end) — anchor-aware.
 *
 * <p>Optymalizują effort metryki przez {@link EdgeCache} — używają haversine jako proxy
 * dla speed (BRouter call per swap byłby za drogi).
 */
public class CoverageLocalSearch {

    // MAX_ITER = liczba PRZEBIEGÓW. 2-opt stosuje wiele reverse'ów per przebieg (1 na pozycję i),
    // więc to NIE limit całkowitych ruchów. 300 przebiegów wystarcza by odplątać trasę ~200+ wp.
    private static final int MAX_ITER = 300;
    private static final double IMPROVE_EPSILON = 0.01;
    /** ≤ tylu wp = pełny O(n²) skan (jak dotąd, bez regresji dla typowych tras). */
    private static final int FULL_SCAN_MAX = 500;
    /** > FULL_SCAN_MAX: 2-opt/relocate skanują tylko OKNO sąsiedztwa (±tyle pozycji). Trasy Hilberta/
     * projekcji są locality-preserving → korzystne ruchy są lokalne, więc okno zachowuje efekt, a
     * tnie O(n²)→O(n×W). Cała Polska (~2000 wp): ~25× szybciej (było 22 min na samym 2-opt). */
    private static final int NEIGHBOR_WINDOW = 80;

    /**
     * 2-opt: reverse segmentów. Dla każdej pary edges (i, j), sprawdź czy reverse [i+1..j]
     * daje krótszy total haversine. Anchors (start/end) niezmienne. Duże trasy: okno sąsiedztwa.
     *
     * @return liczba zastosowanych swapów
     */
    public static int twoOpt(List<double[]> route) {
        int n = route.size();
        return twoOpt(route, n <= FULL_SCAN_MAX ? n : NEIGHBOR_WINDOW);
    }

    /** 2-opt z jawnym oknem skanowania (j ≤ i+window). window ≥ n = pełny skan. */
    public static int twoOpt(List<double[]> route, int window) {
        return twoOptRange(route, 0, route.size() - 2, window);
    }

    /**
     * 2-opt na PODZAKRESIE pozycji `i ∈ [loIdx, hiIdx]` (włącznie), `j ≤ i+window`. Używany w seed grow
     * jako tani incremental po dodaniu batch=20 obszarów — skanujemy tylko OKOLICĘ ostatnio wstawionych
     * wp + ich sąsiadów. Pełny twoOpt(route) co 5 batchy zostaje jako bezpiecznik.
     *
     * <p>Dla seed grow z 34746 obszarów (Francja): pełny twoOpt 1737× w pętli batch ≈ 4 h. Incremental
     * z window=80 wokół ostatnich +80 sąsiadów = ~6 400 par × 10 outer-pass × 100 ns ≈ 6 ms per call,
     * razy 1737 ≈ 10 s. Pełny co 5 batchy = 347× × ~3 s = 17 min. Razem ~17 min vs 4 h.
     */
    public static int twoOptRange(List<double[]> route, int loIdx, int hiIdx, int window) {
        if (route.size() < 4) return 0;
        int lo = Math.max(0, loIdx);
        int hi = Math.min(route.size() - 2, hiIdx);
        if (lo > hi) return 0;
        int swaps = 0;
        boolean improved = true;
        int iter = 0;
        while (improved && iter++ < MAX_ITER) {
            improved = false;
            for (int i = lo; i <= hi; i++) {
                int jMax = Math.min(i + 1 + window, route.size() - 1);
                for (int j = i + 2; j < jMax; j++) {
                    double[] a = route.get(i);
                    double[] b = route.get(i + 1);
                    double[] c = route.get(j);
                    double[] d = route.get(j + 1);
                    double oldCost = WaypointSelector.haversineKm(a, b) + WaypointSelector.haversineKm(c, d);
                    double newCost = WaypointSelector.haversineKm(a, c) + WaypointSelector.haversineKm(b, d);
                    if (newCost < oldCost - IMPROVE_EPSILON) {
                        Collections.reverse(route.subList(i + 1, j + 1));
                        swaps++;
                        improved = true;
                        break; // next i (NIE restart całego skanu — wiele reverse'ów per przebieg)
                    }
                }
            }
        }
        return swaps;
    }

    /**
     * Wygoda: 2-opt incremental — skanuje OKOLICĘ {@code fromIdx ± window}. Używany po dodaniu batch
     * w seed grow zamiast pełnego twoOpt (który dla 30k wp robi ~3-10 s per call × 1737 batchy = h).
     */
    public static int twoOptIncremental(List<double[]> route, int fromIdx, int window) {
        return twoOptRange(route, fromIdx - window, fromIdx + window, window);
    }

    /**
     * Relocate: przesuń pojedynczy punkt w inne miejsce jeśli skraca trasę.
     * Anchors (i=0, i=last) niezmienne.
     */
    public static int relocate(List<double[]> route) {
        int n = route.size();
        return relocate(route, n <= FULL_SCAN_MAX ? n : NEIGHBOR_WINDOW);
    }

    /** Relocate z jawnym oknem (kandydaci wstawienia w [i-window, i+window]). window ≥ n = pełny skan. */
    public static int relocate(List<double[]> route, int window) {
        if (route.size() < 4) return 0;
        int moves = 0;
        boolean improved = true;
        int iter = 0;
        while (improved && iter++ < MAX_ITER) {
            improved = false;
            for (int i = 1; i < route.size() - 1; i++) {
                double[] prev = route.get(i - 1);
                double[] cur = route.get(i);
                double[] next = route.get(i + 1);
                double removed = WaypointSelector.haversineKm(prev, cur)
                        + WaypointSelector.haversineKm(cur, next)
                        - WaypointSelector.haversineKm(prev, next);
                int bestJ = -1;
                double bestDelta = -IMPROVE_EPSILON;
                int lo = Math.max(0, i - window);
                int hi = Math.min(route.size() - 1, i + window);
                for (int j = lo; j < hi; j++) {
                    if (j == i - 1 || j == i) continue;
                    double[] a = route.get(j);
                    double[] b = route.get(j + 1);
                    double added = WaypointSelector.haversineKm(a, cur)
                            + WaypointSelector.haversineKm(cur, b)
                            - WaypointSelector.haversineKm(a, b);
                    double delta = added - removed;
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestJ = j;
                    }
                }
                if (bestJ >= 0) {
                    double[] w = route.remove(i);
                    int insertAt = bestJ < i ? bestJ + 1 : bestJ;
                    route.add(insertAt, w);
                    moves++;
                    improved = true;
                    break;
                }
            }
        }
        return moves;
    }

    /**
     * Swap: zamień dwa punkty miejscami jeśli skraca trasę. Anchors niezmienne.
     */
    public static int swap(List<double[]> route) {
        if (route.size() < 5) return 0;
        int swaps = 0;
        boolean improved = true;
        int iter = 0;
        while (improved && iter++ < MAX_ITER) {
            improved = false;
            for (int i = 1; i < route.size() - 2; i++) {
                for (int j = i + 1; j < route.size() - 1; j++) {
                    double before = costAroundSwap(route, i, j);
                    Collections.swap(route, i, j);
                    double after = costAroundSwap(route, i, j);
                    if (after < before - IMPROVE_EPSILON) {
                        swaps++;
                        improved = true;
                        break;
                    } else {
                        Collections.swap(route, i, j); // revert
                    }
                }
                if (improved) break;
            }
        }
        return swaps;
    }

    private static double costAroundSwap(List<double[]> route, int i, int j) {
        // Suma haversine 4 edges wokół pozycji i i j (lub adjacent)
        double cost = 0;
        if (i > 0) cost += WaypointSelector.haversineKm(route.get(i - 1), route.get(i));
        cost += WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
        if (j != i + 1 && j > 0) cost += WaypointSelector.haversineKm(route.get(j - 1), route.get(j));
        if (j < route.size() - 1) cost += WaypointSelector.haversineKm(route.get(j), route.get(j + 1));
        return cost;
    }

}
