package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.PlanningIntent;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;
import velomarker.exception.PlanningSessionMissingException;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.in.RouteDraftUseCase;
import velomarker.port.in.RouteDraftUseCase.RouteDraftCreateCommand;
import velomarker.port.in.planning.PlanningSessionUseCase;
import velomarker.port.in.planning.SavePlanAsExpeditionUseCase;
import velomarker.port.in.planning.UpdatePlanningDayUseCase;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.PlanningSessionDayRepository;
import velomarker.port.out.planning.PlanningSessionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja CRUD na sesji asystenta + edycja dni + zapis jako wyprawa.
 *
 * <p>Edycja dnia: re-route przez BRouter, re-sample elevation, update tabeli planning.session_day.
 * Zapis jako wyprawa: wszystkie dni sesji → osobne wpisy w routes.route_draft z wspólnym group_id
 * (kompatybilne z istniejącym widokiem „Wyprawy" w UI manualnym).
 */
public class PlanningSessionService implements PlanningSessionUseCase, UpdatePlanningDayUseCase, SavePlanAsExpeditionUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlanningSessionService.class);

    private final PlanningSessionRepository sessionRepository;
    private final PlanningSessionDayRepository dayRepository;
    private final CalculateRouteUseCase routeUseCase;
    private final ElevationDataSource elevation;
    private final RouteDraftUseCase routeDraftUseCase;

    public PlanningSessionService(PlanningSessionRepository sessionRepository,
                                  PlanningSessionDayRepository dayRepository,
                                  CalculateRouteUseCase routeUseCase,
                                  ElevationDataSource elevation,
                                  RouteDraftUseCase routeDraftUseCase) {
        this.sessionRepository = sessionRepository;
        this.dayRepository = dayRepository;
        this.routeUseCase = routeUseCase;
        this.elevation = elevation;
        this.routeDraftUseCase = routeDraftUseCase;
    }

    @Override
    public Optional<PlanningSession> getSession(UUID userId) {
        return sessionRepository.findByUserId(userId);
    }

    @Override
    public PlanningSession setIntent(UUID userId, PlanningIntent intent) {
        PlanningSession session = sessionRepository.findByUserId(userId)
                .orElseGet(() -> PlanningSession.freshFor(userId));
        session.setIntent(intent);
        PlanningSession saved = sessionRepository.save(session);
        // Zmiana intentu = wykasowanie policzonych dni (CASCADE byłoby przy DELETE; tu manualnie).
        dayRepository.deleteBySessionId(saved.id());
        return saved;
    }

    @Override
    public PlanningSession updateForm(UUID userId, RoutePreferences delta) {
        PlanningSession session = sessionRepository.findByUserId(userId)
                .orElseGet(() -> PlanningSession.freshFor(userId));
        session.mergePreferences(delta);
        return sessionRepository.save(session);
    }

    @Override
    public void reset(UUID userId) {
        sessionRepository.deleteByUserId(userId);
    }

    // ===== UpdatePlanningDayUseCase =====

    @Override
    public PlanningSessionDay updateDay(UUID userId, int dayNumber, List<Waypoint> newWaypoints) {
        PlanningSession session = sessionRepository.findByUserId(userId)
                .orElseThrow(() -> new PlanningSessionMissingException(userId));
        PlanningSessionDay existing = dayRepository.findBySessionIdAndDayNumber(session.id(), dayNumber)
                .orElseThrow(() -> new PlanningSessionMissingException("Day " + dayNumber + " not found"));

        if (newWaypoints == null || newWaypoints.size() < 2) {
            throw new IllegalArgumentException("Day must have at least 2 waypoints");
        }

        List<double[]> brouterInput = newWaypoints.stream().map(Waypoint::toLngLat).toList();
        RouteCalculation calc = routeUseCase.calculate(
                new CalculateRouteUseCase.CalculateRouteCommand(brouterInput, existing.profile()));
        var profile = elevation.sample(calc.coordinates());

        PlanningSessionDay updated = existing.withEditedGeometry(
                calc.coordinates(),
                newWaypoints,
                calc.distanceKm(),
                (int) Math.round(profile.gainM()),
                (int) Math.round(profile.lossM())
        );
        return dayRepository.save(updated);
    }

    // ===== SavePlanAsExpeditionUseCase =====

    @Override
    public SavePlanAsExpeditionUseCase.ExpeditionResult saveAsExpedition(UUID userId, String groupName) {
        PlanningSession session = sessionRepository.findByUserId(userId)
                .orElseThrow(() -> new PlanningSessionMissingException(userId));
        List<PlanningSessionDay> days = dayRepository.findBySessionId(session.id());
        if (days.isEmpty()) {
            throw new PlanningSessionMissingException("No calculated days for session " + session.id());
        }

        UUID groupId = UUID.randomUUID();
        List<UUID> draftIds = new ArrayList<>(days.size());
        for (PlanningSessionDay day : days) {
            String name = groupName + " – Dzień " + day.dayNumber();
            // Konwersja waypointów dnia do formatu encoded (Polyline3DCodec) — używamy istniejącego
            // pola String w route_draft.waypoints. Najprościej: zapisz jako lng,lat;lng,lat (csv).
            String encodedWaypoints = encodeWaypoints(day.waypoints());
            var created = routeDraftUseCase.create(new RouteDraftCreateCommand(
                    userId,
                    name,
                    day.geometry(),
                    day.profile(),
                    day.distanceKm(),
                    day.elevationGain(),
                    day.elevationLoss(),
                    groupId,
                    groupName,
                    day.dayNumber(),
                    encodedWaypoints
            ));
            draftIds.add(created.id());
        }
        log.info("Saved expedition group={} days={} for user={}", groupId, draftIds.size(), userId);
        return new SavePlanAsExpeditionUseCase.ExpeditionResult(groupId, draftIds);
    }

    private static String encodeWaypoints(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Waypoint w : waypoints) {
            if (sb.length() > 0) sb.append(';');
            sb.append(w.lng()).append(',').append(w.lat());
        }
        return sb.toString();
    }
}
