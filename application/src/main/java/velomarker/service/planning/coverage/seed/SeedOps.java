package velomarker.service.planning.coverage.seed;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Wspólne operacje na trasie seeda: przebudowa kolejności Hilberta i podmiana entry-pointu gminy. */
public final class SeedOps {

    private final HilbertOrdering ordering;

    public SeedOps(HilbertOrdering ordering) {
        this.ordering = ordering;
    }

    public void rebuildOrdered(SeedRoute seed) {
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

    public void swapEntry(List<SeedSel> selected, double[] oldPoint, double[] newPoint, List<double[]> baseline) {
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
