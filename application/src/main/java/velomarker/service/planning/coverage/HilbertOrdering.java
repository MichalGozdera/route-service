package velomarker.service.planning.coverage;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;

/**
 * Porządkowanie obszarów wzdłuż trasy krzywą Hilberta (space-filling) nad bbox kandydatów.
 * Locality-preserving: sąsiednie indeksy = sąsiednie w 2D → grow zbiera ciągłą plamę (nie rozsiane
 * punkty), niezależnie od kształtu regionu (koło PL / korytarz DE-CZ). Bbox liczone raz per plan.
 */
final class HilbertOrdering {

    private static final int ORDER = 16; // grid 65536² per oś

    private double minLng, minLat, maxLng, maxLat;

    /** Bbox centroidów kandydatów (do normalizacji punktów dla krzywej). Liczone raz per plan. */
    void computeBbox(List<UnvisitedArea> pool) {
        minLng = Double.MAX_VALUE; minLat = Double.MAX_VALUE;
        maxLng = -Double.MAX_VALUE; maxLat = -Double.MAX_VALUE;
        for (UnvisitedArea a : pool) {
            minLng = Math.min(minLng, a.lng()); maxLng = Math.max(maxLng, a.lng());
            minLat = Math.min(minLat, a.lat()); maxLat = Math.max(maxLat, a.lat());
        }
        if (pool.isEmpty()) { minLng = 0; minLat = 0; maxLng = 1; maxLat = 1; }
    }

    /** Klucz porządkowania punktu [lng,lat] = indeks na krzywej Hilberta nad bbox. */
    double orderKey(double[] point) {
        int n = 1 << ORDER;
        double fx = (maxLng > minLng) ? (point[0] - minLng) / (maxLng - minLng) : 0.0;
        double fy = (maxLat > minLat) ? (point[1] - minLat) / (maxLat - minLat) : 0.0;
        int x = (int) Math.max(0, Math.min(n - 1, Math.round(fx * (n - 1))));
        int y = (int) Math.max(0, Math.min(n - 1, Math.round(fy * (n - 1))));
        return (double) hilbertDistance(n, x, y);
    }

    /** Klasyczny xy2d (Wikipedia): (x,y) → odległość d na krzywej Hilberta rzędu n. */
    private static long hilbertDistance(int n, int x, int y) {
        long d = 0;
        for (int s = n / 2; s > 0; s /= 2) {
            int rx = (x & s) > 0 ? 1 : 0;
            int ry = (y & s) > 0 ? 1 : 0;
            d += (long) s * s * ((3L * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) { x = n - 1 - x; y = n - 1 - y; }
                int t = x; x = y; y = t;
            }
        }
        return d;
    }
}
