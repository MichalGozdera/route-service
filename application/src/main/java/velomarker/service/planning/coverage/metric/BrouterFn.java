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

import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.Waypoint;

import java.util.List;

// Funkcja routingu BRouter dla plannera pokrycia (flaga computeStats włącza agregację statystyk trasy).
@FunctionalInterface
public interface BrouterFn {
    RouteCalculation route(List<Waypoint> waypoints, String profile, boolean computeStats);
}
