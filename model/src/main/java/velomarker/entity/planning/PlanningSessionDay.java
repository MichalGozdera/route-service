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
 * {@code coveredAreaIds} = ID gmin ZALICZONYCH przez ten dzień (kryterium kredytu ≥200 m, port JTS) —
 * źródło prawdy dla kolorowania na froncie (v3.18, zamiast re-derywacji turfem plain-touch).
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
        RouteStats stats,
        List<Integer> coveredAreaIds
) {

    public PlanningSessionDay withEditedGeometry(List<double[]> newGeometry, List<Waypoint> newWaypoints,
                                                 double newDistanceKm,
                                                 int newGain, int newLoss) {
        // coveredAreaIds zachowane ze stanu sprzed edycji — przy live-edit dnia front i tak przelicza
        // pokrycie sam (warstwa edycji); backendowe ID są dla podglądu PLANU.
        return new PlanningSessionDay(id, sessionId, dayNumber, newGeometry, newWaypoints,
                newDistanceKm, newGain, newLoss, profile, Instant.now(), stats, coveredAreaIds);
    }
}
