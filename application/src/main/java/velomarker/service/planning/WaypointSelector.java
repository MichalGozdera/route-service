package velomarker.service.planning;

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

import velomarker.service.GeoMath;

import java.util.List;

// Helpery geometryczne planowania trasy: suma odcinków prostych + haversine (deleguje do GeoMath).
public class WaypointSelector {

    public double straightLineDistanceKm(List<double[]> waypoints) {
        double sum = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            sum += haversineKm(waypoints.get(i - 1), waypoints.get(i));
        }
        return sum;
    }

    public static double haversineKm(double[] a, double[] b) {
        return GeoMath.haversineKm(a, b);
    }
}
