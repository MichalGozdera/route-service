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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// CRUD na sesji asystenta + edycja dni + zapis jako wyprawa.
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
        dayRepository.deleteBySessionId(saved.id());
        return saved;
    }

    @Override
    public PlanningSession updateForm(UUID userId, RoutePreferences delta) {
        PlanningSession session = sessionRepository.findByUserId(userId)
                .orElseGet(() -> PlanningSession.freshFor(userId));
        // Zmiana kształtu formularza unieważnia policzony wynik (żeby po reloadzie nie wracał stary ślad).
        boolean shapeChanged = shapeChanged(session.preferences(), delta);
        session.mergePreferences(delta);
        if (shapeChanged) {
            dayRepository.deleteBySessionId(session.id());
            session.setLastTaskId(null);
        }
        return sessionRepository.save(session);
    }

    /** Czy delta zmienia KSZTAŁT planu (lustro frontowego touchesShape). Budżet (days/kmPerDay/
     *  elevationPerDayM/profile) NIE liczy się; waypointy porównywane TYLKO po współrzędnych (ignoruj name,
     *  by zmiana języka/re-geokodowanie nie kasowało wyniku). Delta jest częściowa → bierzemy pola non-null. */
    private static boolean shapeChanged(RoutePreferences cur, RoutePreferences d) {
        if (d == null) return false;
        if (Boolean.TRUE.equals(d.clearStart()) || Boolean.TRUE.equals(d.clearEnd())) return true;
        return (d.countryIds() != null && !Objects.equals(d.countryIds(), cur.countryIds()))
                || (d.levelIds() != null && !Objects.equals(d.levelIds(), cur.levelIds()))
                || (d.specialGroupIds() != null && !Objects.equals(d.specialGroupIds(), cur.specialGroupIds()))
                || (d.loop() != null && !Objects.equals(d.loop(), cur.loop()))
                || (d.start() != null && !coordsEq(d.start(), cur.start()))
                || (d.end() != null && !coordsEq(d.end(), cur.end()))
                || (d.via() != null && !viaCoordsEq(d.via(), cur.via()));
    }

    private static boolean coordsEq(Waypoint a, Waypoint b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.lng() == b.lng() && a.lat() == b.lat(); // BEZ name (display-only)
    }

    private static boolean viaCoordsEq(List<Waypoint> a, List<Waypoint> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!coordsEq(a.get(i), b.get(i))) return false;
        return true;
    }

    @Override
    public void reset(UUID userId) {
        sessionRepository.deleteByUserId(userId);
    }

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
                    encodedWaypoints,
                    day.stats()
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
