package velomarker.service.planning.coverage.prep;

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

import velomarker.entity.planning.UnvisitedArea;
// Wynik scoringu gminy względem bazowej trasy.
public class AreaCandidate {
    public final UnvisitedArea area;
    public final boolean intersected;
    public final double detourStraightKm;

    public boolean isIntersected() { return intersected; }
    public double getDetourStraightKm() { return detourStraightKm; }
    public UnvisitedArea getArea() { return area; }

    public AreaCandidate(UnvisitedArea area, boolean intersected, double detourStraightKm) {
        this.area = area;
        this.intersected = intersected;
        this.detourStraightKm = detourStraightKm;
    }
}
