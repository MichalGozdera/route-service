package velomarker.service.planning.coverage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Wspólne operacje na trasie seeda: przebudowa kolejności wg krzywej Hilberta ({@code rebuildOrdered} — UŻYWANE
 * TYLKO w init-grow jako construction) + podmiana entry-pointu gminy. Bezstanowe poza wstrzykniętym
 * {@link HilbertOrdering} — jedna instancja per plan.
 */
final class SeedOps {

    private final HilbertOrdering ordering;

    SeedOps(HilbertOrdering ordering) {
        this.ordering = ordering;
    }

    /**
     * Przebuduj {@code route} = anchory + selected entry-pointy posortowane wg klucza Hilberta (anchory na brzegach).
     * UŻYWANE TYLKO w init-grow (construction seeda od zera — daje 2-optowi geograficznie zwarty start). Gdzie indziej
     * (anchor/finalize/holefill/fixIslands) PSUŁO zoptymalizowaną trasę (Hilbert-reset gorszy niż 2-opt) — tam in-place
     * / cheapest-insert.
     */
    void rebuildOrdered(SeedRoute seed) {
        List<double[]> anchorOnly = seed.anchorOnly();
        List<SeedSel> selected = seed.selected();
        List<double[]> route = seed.route();
        route.clear();
        record RoutePt(double[] p, double key) {}
        List<RoutePt> ordered = new ArrayList<>(anchorOnly.size() + selected.size());
        for (int i = 0; i < anchorOnly.size(); i++) {
            double key = (i == 0) ? Double.NEGATIVE_INFINITY
                    : (i == anchorOnly.size() - 1) ? Double.MAX_VALUE
                    : ordering.orderKey(anchorOnly.get(i));
            ordered.add(new RoutePt(anchorOnly.get(i), key));
        }
        for (SeedSel s : selected) ordered.add(new RoutePt(s.point(), s.proj()));
        ordered.sort(Comparator.comparingDouble(RoutePt::key));
        for (RoutePt rp : ordered) route.add(rp.p());
    }

    /** Podmień entry-point gminy w {@code selected} (identity po starym punkcie) + zaktualizuj distBase. */
    void swapEntry(List<SeedSel> selected, double[] oldPoint, double[] newPoint, List<double[]> baseline) {
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).point() == oldPoint) {
                SeedSel old = selected.get(i);
                selected.set(i, new SeedSel(old.area(), newPoint, ordering.orderKey(newPoint),
                        old.score(), GeometryUtil.minDistToBaselineKm(newPoint, baseline)));
                return;
            }
        }
    }
}
