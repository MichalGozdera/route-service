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

import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;
import velomarker.exception.PlanningSessionNotReadyException;
import velomarker.port.in.CalculateRouteUseCase;

import java.util.ArrayList;
import java.util.List;

// Baseline-probe: BRouter na samych anchorach (start→via→meta) = dolna granica trasy + geometria korytarza.
public final class BaselineComputer {

    private final CalculateRouteUseCase routeUseCase;
    private final WaypointSelector waypointSelector;

    public BaselineComputer(CalculateRouteUseCase routeUseCase, WaypointSelector waypointSelector) {
        this.routeUseCase = routeUseCase;
        this.waypointSelector = waypointSelector;
    }

    public static List<Waypoint> buildAnchorWaypoints(RoutePreferences prefs) {
        List<Waypoint> wps = new ArrayList<>();
        wps.add(prefs.start());
        if (prefs.via() != null) wps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) wps.add(prefs.start());
        else if (prefs.end() != null) wps.add(prefs.end());
        else wps.add(prefs.start());
        return wps;
    }

    public BaselineProbe compute(RoutePreferences prefs, String profile) {
        List<double[]> anchors = new ArrayList<>();
        for (Waypoint w : buildAnchorWaypoints(prefs)) anchors.add(w.toLngLat());
        double straight = waypointSelector.straightLineDistanceKm(anchors);
        try {
            RouteCalculation r = routeUseCase.calculate(
                    new CalculateRouteUseCase.CalculateRouteCommand(anchors, profile, false));
            return new BaselineProbe(r.distanceKm(), ascentFromCoords(r.coordinates()), straight, r.coordinates());
        } catch (RuntimeException e) {
            throw new PlanningSessionNotReadyException(
                    "Nie można policzyć trasy start→via→meta (" + e.getMessage() + ") — popraw start/metę/via.");
        }
    }

    private static double ascentFromCoords(List<double[]> coords) {
        if (coords == null || coords.size() < 2) return 0;
        double gain = 0;
        for (int i = 1; i < coords.size(); i++) {
            double[] a = coords.get(i - 1), b = coords.get(i);
            if (a.length < 3 || b.length < 3) return 0;
            double dz = b[2] - a[2];
            if (dz > 0) gain += dz;
        }
        return gain;
    }
}
