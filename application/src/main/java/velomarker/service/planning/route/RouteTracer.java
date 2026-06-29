package velomarker.service.planning.route;

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

import velomarker.exception.PlanningSessionNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Trasowanie AB/FREESTYLE: chunked BRouter po waypointach + weryfikacja dotarcia do anchorów. */
public final class RouteTracer {

    private static final Logger log = LoggerFactory.getLogger(RouteTracer.class);

    private final ChunkedBrouterRouter router;
    private final Consumer<UUID> checkCancel;
    private final UUID taskId;
    private final RoutePreferences prefs;
    private final List<Waypoint> waypoints;
    private final String profile;
    private final double maxGapKm;

    public RouteTracer(ChunkedBrouterRouter router, Consumer<UUID> checkCancel, UUID taskId,
                RoutePreferences prefs, List<Waypoint> waypoints, String profile, double maxGapKm) {
        this.router = router;
        this.checkCancel = checkCancel;
        this.taskId = taskId;
        this.prefs = prefs;
        this.waypoints = waypoints;
        this.profile = profile;
        this.maxGapKm = maxGapKm;
    }

    public RouteCalculation trace() {
        checkCancel.accept(taskId);
        List<double[]> input = waypoints.stream().map(Waypoint::toLngLat).toList();
        RouteCalculation calc = router.route(taskId, input, profile);
        assertAnchorsReached(calc.coordinates(), prefs, maxGapKm);
        return calc;
    }

    private void assertAnchorsReached(List<double[]> geometry, RoutePreferences prefs, double maxGapKm) {
        if (geometry.isEmpty() || prefs.start() == null) return;
        double maxGap = 0;
        String worstAnchor = null;
        double startGap = WaypointSelector.haversineKm(geometry.get(0), prefs.start().toLngLat());
        if (startGap > maxGap) { maxGap = startGap; worstAnchor = "start:" + (prefs.start().name() != null ? prefs.start().name() : "?"); }
        Waypoint endWp = Boolean.TRUE.equals(prefs.loop()) ? prefs.start() : prefs.end();
        if (endWp != null) {
            double endGap = WaypointSelector.haversineKm(geometry.get(geometry.size() - 1), endWp.toLngLat());
            if (endGap > maxGap) { maxGap = endGap; worstAnchor = "end:" + (endWp.name() != null ? endWp.name() : "?"); }
        }
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) {
                double[] target = v.toLngLat();
                double minGap = Double.MAX_VALUE;
                for (double[] g : geometry) {
                    double d = WaypointSelector.haversineKm(g, target);
                    if (d < minGap) {
                        minGap = d;
                        if (minGap <= 0.5) break;
                    }
                }
                if (minGap > maxGap) {
                    maxGap = minGap;
                    worstAnchor = "via:" + (v.name() != null ? v.name() : "?");
                }
            }
        }
        log.info("Reachability (user anchors only): max gap {} km (worst: {})",
                new Object[]{String.format("%.2f", maxGap), worstAnchor});
        if (maxGap > maxGapKm) {
            throw new PlanningSessionNotReadyException(
                    "BRouter nie dotarł do " + worstAnchor + " (gap " + String.format("%.1f", maxGap) + " km). " +
                    "Najprawdopodobniej target-island. Przesuń ten punkt o ~1 km do najbliższej drogi.");
        }
    }
}
