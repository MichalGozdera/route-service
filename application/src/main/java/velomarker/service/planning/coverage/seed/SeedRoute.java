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

import java.util.List;

/** Mutowalny stan trasy seeda: punkty trasy, wybrane gminy, kotwice i baseline. */
public final class SeedRoute {

    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final List<double[]> anchorOnly;
    private final List<double[]> anchors;
    private final List<double[]> baseline;
    private final double[] baseCum;

    public SeedRoute(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
              List<double[]> anchors, List<double[]> baseline, double[] baseCum) {
        this.route = route;
        this.selected = selected;
        this.anchorOnly = anchorOnly;
        this.anchors = anchors;
        this.baseline = baseline;
        this.baseCum = baseCum;
    }

    public List<double[]> route() { return route; }
    public List<SeedSel> selected() { return selected; }
    public List<double[]> anchorOnly() { return anchorOnly; }
    public List<double[]> anchors() { return anchors; }
    public List<double[]> baseline() { return baseline; }
    public double[] baseCum() { return baseCum; }
}
