package velomarker.entity.planning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pojedynczy dzień policzonej wyprawy. Geometria 3D (lng,lat,ele) kodowana przez Polyline3DCodec
 * w adapterze (tutaj surowy {@code List<double[]>}). {@code waypoints} = punkty „kotwic" które user
 * może edytować na mapie po otwarciu dnia.
 */
public record PlanningSessionDay(
        UUID id,
        UUID sessionId,
        int dayNumber,
        List<double[]> geometry,
        List<Waypoint> waypoints,
        Double distanceKm,
        Integer elevationGain,
        Integer elevationLoss,
        String profile,
        Instant editedAt
) {

    public PlanningSessionDay withEditedGeometry(List<double[]> newGeometry, List<Waypoint> newWaypoints,
                                                 double newDistanceKm,
                                                 int newGain, int newLoss) {
        return new PlanningSessionDay(id, sessionId, dayNumber, newGeometry, newWaypoints,
                newDistanceKm, newGain, newLoss, profile, Instant.now());
    }
}
