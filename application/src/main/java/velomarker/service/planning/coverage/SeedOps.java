package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Wspólne operacje na trasie seeda, dzielone przez klasy odpowiedzialności (cięcie spurów / anchor / dobieranie /
 * trim): 2-opt z logiem, przebudowa kolejności wg krzywej Hilberta, podmiana entry-pointu gminy. Bezstanowe poza
 * wstrzykniętymi kolaboratorami — jedna instancja per plan.
 */
final class SeedOps {

    private static final Logger log = LoggerFactory.getLogger(SeedOps.class);

    private final HilbertOrdering ordering;
    private final RouteMetrics metrics;
    private final boolean debugGeoJson;

    SeedOps(HilbertOrdering ordering, RouteMetrics metrics, boolean debugGeoJson) {
        this.ordering = ordering;
        this.metrics = metrics;
        this.debugGeoJson = debugGeoJson;
    }

    /** 2-opt (haversine, tylko skraca); gdy debugGeoJson — loguje Δ długości i fazę. */
    void twoOpt(List<double[]> route, String phase) {
        if (!debugGeoJson) {
            CoverageLocalSearch.twoOpt(route);
            return;
        }
        double kmBefore = metrics.haversineKm(route);
        int wp = route.size();
        CoverageLocalSearch.twoOpt(route);
        double kmAfter = metrics.haversineKm(route);
        log.info("Coverage 2-OPT [{}]: havKm {}→{} (Δ{}), wps={}", new Object[]{phase,
                Math.round(kmBefore), Math.round(kmAfter), Math.round(kmAfter - kmBefore), wp});
    }

    /** Przebuduj {@code route} = anchory + selected entry-pointy posortowane wg klucza Hilberta (anchory na brzegach). */
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
