package velomarker.service.planning.coverage.metric;

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

public record EdgeInfo(double distanceKm, double climbM, double effort, java.util.List<double[]> geometry,
                       double[] crosspointA, double[] crosspointB) {
    public EdgeInfo(double distanceKm, double climbM, double effort) {
        this(distanceKm, climbM, effort, java.util.List.of(), null, null);
    }
    public EdgeInfo(double distanceKm, double climbM, double effort, java.util.List<double[]> geometry) {
        this(distanceKm, climbM, effort, geometry, null, null);
    }
}
