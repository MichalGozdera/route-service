package velomarker.service.planning.alns2;

import velomarker.service.planning.WaypointSelector;

import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Local search dla ALNS2: 2-opt, relocate, swap. Wszystkie operatorów zachowują
 * route[0] (start) i route[route.size()-1] (end) — anchor-aware.
 *
 * <p>Optymalizują effort metryki przez {@link EdgeCache} — używają haversine jako proxy
 * dla speed (BRouter call per swap byłby za drogi).
 */
public class Alns2LocalSearch {

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
        if (route.size() < 4) return 0;
        int swaps = 0;
        boolean improved = true;
        int iter = 0;
        while (improved && iter++ < MAX_ITER) {
            improved = false;
            for (int i = 0; i < route.size() - 2; i++) {
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

    /** Suma haversine wszystkich edges trasy (proxy dla effort). */
    public static double totalHaversine(List<double[]> route) {
        return totalCost(route, p -> 0); // 0 unused
    }

    private static double totalCost(List<double[]> route, ToDoubleFunction<double[]> ignored) {
        double sum = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            sum += WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
        }
        return sum;
    }
}
