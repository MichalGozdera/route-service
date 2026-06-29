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

import java.util.List;

/** Scoring gminy względem trasy bazowej: czy baseline ją przecina i koszt detoru (2× dystans + 0.2 km). */
public final class CoverageAreaSelection {

    private CoverageAreaSelection() {}

    public static AreaCandidate scoreAreaAgainstBaseline(UnvisitedArea area, List<double[]> baselineGeom, boolean intersected) {
        double[] centroid = {area.lng(), area.lat()};
        int step = Math.max(1, baselineGeom.size() / 3000);
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < baselineGeom.size(); i += step) {
            double d = WaypointSelector.haversineKm(baselineGeom.get(i), centroid);
            if (d < minDist) minDist = d;
        }
        double detour = intersected ? 0 : (2 * minDist + 0.2);
        return new AreaCandidate(area, intersected, detour);
    }
}
