package velomarker.entity.planning;

import velomarker.entity.RouteStats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pojedynczy dzień policzonej wyprawy. Geometria 3D (lng,lat,ele) kodowana przez Polyline3DCodec
 * w adapterze (tutaj surowy {@code List<double[]>}). {@code waypoints} = punkty „kotwic" które user
 * może edytować na mapie po otwarciu dnia. {@code stats} = snapshot RouteStats wycięty dla okna dnia
 * z pełnej trasy (FE renderuje overlay nawierzchni / typów dróg bez ponownego wywołania BRoutera).
 * Pokrycie gmin liczy front (turf.js) z geometrii — backend go nie zwraca.
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
        Instant editedAt,
        RouteStats stats
) {

    public PlanningSessionDay withEditedGeometry(List<double[]> newGeometry, List<Waypoint> newWaypoints,
                                                 double newDistanceKm,
                                                 int newGain, int newLoss) {
        return new PlanningSessionDay(id, sessionId, dayNumber, newGeometry, newWaypoints,
                newDistanceKm, newGain, newLoss, profile, Instant.now(), stats);
    }
}
