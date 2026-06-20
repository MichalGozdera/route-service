package velomarker.service.planning;

import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.Waypoint;

import java.util.List;

/** Wynik trasowania: policzona trasa + finalne waypointy. */
record TraceResult(RouteCalculation calc, List<Waypoint> finalWaypoints) {}
