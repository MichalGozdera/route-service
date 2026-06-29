package velomarker.entity.planning;

import velomarker.entity.RouteStats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ostatnia trasa rysowana RĘCZNIE przez użytkownika — JEDNA per user (UNIQUE user_id w DB).
 * Te same dane co {@link PlanningSessionDay}, ale bez {@code sessionId}/{@code dayNumber} — kluczem
 * jest {@code userId}. Geometria 3D (lng,lat,ele) kodowana przez Polyline3DCodec w adapterze
 * (tutaj surowy {@code List<double[]>}). {@code waypoints} = punkty kotwic które user stawiał na mapie.
 * {@code stats} = scalony RouteStats całej trasy (FE renderuje overlay nawierzchni/dróg bez BRoutera).
 *
 * <p>Zastępuje dawny localStorage {@code velomarker_last_planned_route}: spójność + multi-device +
 * natychmiastowy profil wysokości (Z w geometrii).
 */
public record ManualSession(
        UUID id,
        UUID userId,
        List<double[]> geometry,
        List<Waypoint> waypoints,
        Double distanceKm,
        Integer elevationGain,
        Integer elevationLoss,
        String profile,
        RouteStats stats,
        Instant editedAt
) {

    /** Buduje świeży snapshot do zapisu (id losowy = używany tylko przy INSERT; upsert po user_id reużywa istniejący). */
    public static ManualSession create(UUID userId, List<double[]> geometry, List<Waypoint> waypoints,
                                       Double distanceKm, Integer elevationGain, Integer elevationLoss,
                                       String profile, RouteStats stats) {
        return new ManualSession(UUID.randomUUID(), userId, geometry, waypoints,
                distanceKm, elevationGain, elevationLoss, profile, stats, Instant.now());
    }
}
