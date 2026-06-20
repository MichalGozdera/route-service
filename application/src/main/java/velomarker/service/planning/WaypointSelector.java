package velomarker.service.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Dobór i kolejność punktów trasy. Kolejność = heurystyka TSP (nearest-neighbour seed + 2-opt + or-opt),
 * respektująca ustalony start/koniec — eliminuje nawroty dla dowolnego kształtu (nie tylko pasa).
 * Wszystkie punkty to pary [lng, lat]. Czysty kod deterministyczny.
 *
 * <p>Przeniesione 1:1 z assistant-service (bez LLM). Zmieniona tylko paczka i import UnvisitedArea
 * (z {@code velomarker.port.out.UnvisitedArea} → {@code velomarker.entity.planning.UnvisitedArea}).
 */
public class WaypointSelector {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final int TWO_OPT_MAX_PASSES = 60;
    private static final int OR_OPT_MAX_PASSES = 30;
    /**
     * Optymalizuje kolejność obszarów (TSP: NN seed + 2-opt) z kotwicami start/end.
     * Zwraca obszary w nowej kolejności (bez start/end — te dokleja wołający).
     */
    public List<UnvisitedArea> orderAreas(List<UnvisitedArea> areas, double[] start, double[] end) {
        return orderAreas(areas, start, end, () -> false);
    }

    /**
     * Wariant z cancel checkpoint — wątek liczący może być przerwany przez ustawienie flagi
     * w ComputationRegistry. Przy pulach ~kilkuset gmin 2-opt potrafi chodzić minuty; bez tej
     * możliwości anulowanie z UI nie ma efektu (kompilator nie przerwie pętli).
     */
    public List<UnvisitedArea> orderAreas(List<UnvisitedArea> areas, double[] start, double[] end,
                                          BooleanSupplier cancelCheck) {
        if (areas.size() <= 2) {
            return new ArrayList<>(areas);
        }
        List<UnvisitedArea> route = (start != null && end != null)
                ? sortByAxisProjection(areas, start, end)
                : nearestNeighbour(areas, start);
        twoOpt(route, start, end, cancelCheck);
        orOpt(route, start, end, cancelCheck);
        twoOpt(route, start, end, cancelCheck);
        return route;
    }

    /** Seed korytarzowy: kolejność wg rzutu centroidu na oś start→meta (monotonicznie do przodu). */
    private static List<UnvisitedArea> sortByAxisProjection(List<UnvisitedArea> areas, double[] start, double[] end) {
        double dx = end[0] - start[0];
        double dy = end[1] - start[1];
        double len2 = dx * dx + dy * dy;
        List<UnvisitedArea> out = new ArrayList<>(areas);
        out.sort(java.util.Comparator.comparingDouble(a -> {
            if (len2 < 1e-12) {
                return 0.0;
            }
            return ((a.lng() - start[0]) * dx + (a.lat() - start[1]) * dy) / len2;
        }));
        return out;
    }

    /**
     * Or-opt: wyjmuje pojedynczą gminę i wstawia w najlepsze miejsce, jeśli to skraca trasę. Naprawia
     * przypadki, których 2-opt nie łapie — gmina „zgubiona" z dala od swojego rejonu, wymuszająca nawrót.
     */
    private void orOpt(List<UnvisitedArea> route, double[] start, double[] end, BooleanSupplier cancelCheck) {
        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < OR_OPT_MAX_PASSES) {
            if (cancelCheck.getAsBoolean()) return;
            improved = false;
            for (int i = 0; i < route.size(); i++) {
                UnvisitedArea node = route.remove(i);
                int bestPos = i;
                double bestCost = insertionCost(route, node, i, start, end);
                for (int k = 0; k <= route.size(); k++) {
                    double c = insertionCost(route, node, k, start, end);
                    if (c < bestCost - 1e-9) {
                        bestCost = c;
                        bestPos = k;
                    }
                }
                route.add(bestPos, node);
                if (bestPos != i) {
                    improved = true;
                }
            }
        }
    }

    /** Koszt wstawienia node między pozycję k-1 a k (kotwice start/end na brzegach). */
    private static double insertionCost(List<UnvisitedArea> route, UnvisitedArea node, int k,
                                        double[] start, double[] end) {
        double[] p = point(node);
        double[] left = (k == 0) ? start : point(route.get(k - 1));
        double[] right = (k == route.size()) ? end : point(route.get(k));
        return edge(left, p) + edge(p, right) - edge(left, right);
    }

    /** Ray casting: czy punkt [lng,lat] leży wewnątrz obrysu (ring punktów [lng,lat]). */
    public static boolean pointInRing(double[] p, double[][] ring) {
        boolean inside = false;
        double x = p[0];
        double y = p[1];
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0];
            double yi = ring[i][1];
            double xj = ring[j][0];
            double yj = ring[j][1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    private List<UnvisitedArea> nearestNeighbour(List<UnvisitedArea> areas, double[] start) {
        List<UnvisitedArea> remaining = new ArrayList<>(areas);
        List<UnvisitedArea> route = new ArrayList<>(areas.size());
        double[] current = start;
        if (current == null) {
            current = point(remaining.get(0));
            route.add(remaining.remove(0));
        }
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double d = haversineKm(current, point(remaining.get(i)));
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            UnvisitedArea picked = remaining.remove(best);
            route.add(picked);
            current = point(picked);
        }
        return route;
    }

    /** 2-opt: odwracaj odcinki, dopóki skraca łączną długość (z kotwicami start/end). */
    private void twoOpt(List<UnvisitedArea> route, double[] start, double[] end, BooleanSupplier cancelCheck) {
        int n = route.size();
        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < TWO_OPT_MAX_PASSES) {
            if (cancelCheck.getAsBoolean()) return;
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                if ((i & 0x3F) == 0 && cancelCheck.getAsBoolean()) return; // co 64 zewn. iteracje
                for (int j = i + 1; j < n; j++) {
                    double[] prev = (i == 0) ? start : point(route.get(i - 1));
                    double[] next = (j == n - 1) ? end : point(route.get(j + 1));
                    double[] a = point(route.get(i));
                    double[] b = point(route.get(j));
                    double before = edge(prev, a) + edge(b, next);
                    double after = edge(prev, b) + edge(a, next);
                    if (after + 1e-9 < before) {
                        reverse(route, i, j);
                        improved = true;
                    }
                }
            }
        }
    }

    private static void reverse(List<UnvisitedArea> list, int i, int j) {
        while (i < j) {
            UnvisitedArea tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
            i++;
            j--;
        }
    }

    /** Długość krawędzi; otwarty koniec (null kotwica) = 0 (nie liczymy). */
    private static double edge(double[] a, double[] b) {
        return (a == null || b == null) ? 0.0 : haversineKm(a, b);
    }

    private static double[] point(UnvisitedArea a) {
        return new double[]{a.lng(), a.lat()};
    }

    /** Suma odcinków po linii prostej (km) — np. do szacowania długości przed brouter. */
    public double straightLineDistanceKm(List<double[]> waypoints) {
        double sum = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            sum += haversineKm(waypoints.get(i - 1), waypoints.get(i));
        }
        return sum;
    }

    public static double haversineKm(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b[0] - a[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
