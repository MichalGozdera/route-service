package eu.cokeman.velomarker.mapper;

import eu.cokeman.velomarker.openapi.model.PlanTaskDto;
import eu.cokeman.velomarker.openapi.model.PlanTaskStatusDto;
import eu.cokeman.velomarker.openapi.model.PlanningIntentDto;
import eu.cokeman.velomarker.openapi.model.PlanningSessionDayDto;
import eu.cokeman.velomarker.openapi.model.PlanningSessionResponseDto;
import eu.cokeman.velomarker.openapi.model.PlanningSummaryDto;
import eu.cokeman.velomarker.openapi.model.RoutePreferencesDto;
import eu.cokeman.velomarker.openapi.model.WaypointDto;
import org.springframework.stereotype.Component;
import velomarker.entity.planning.PlanTask;
import velomarker.entity.planning.PlanningIntent;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.PlanningSummary;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;

import java.util.List;

/** Mapowanie Domain ↔ REST DTO dla pakietu planning. */
@Component
public class PlanningExternalMapper {

    private final RouteDraftExternalMapper routeDraftExternalMapper;

    public PlanningExternalMapper(RouteDraftExternalMapper routeDraftExternalMapper) {
        this.routeDraftExternalMapper = routeDraftExternalMapper;
    }

    public PlanningSessionResponseDto toSessionResponse(PlanningSession session, List<PlanningSessionDay> days) {
        PlanningSessionResponseDto dto = new PlanningSessionResponseDto();
        dto.setId(session.id());
        dto.setUserId(session.userId());
        dto.setPreferences(toDto(session.preferences()));
        dto.setDays(days == null ? List.of() : days.stream().map(this::toDayDto).toList());
        dto.setCreatedAt(session.createdAt());
        dto.setUpdatedAt(session.updatedAt());
        if (session.intent() != null) {
            dto.setIntent(toDto(session.intent()));
        }
        if (session.lastTaskId() != null) {
            dto.setLastTaskId(session.lastTaskId());
        }
        if (session.summary() != null) {
            dto.setSummary(toDto(session.summary()));
        }
        return dto;
    }

    public PlanningSummaryDto toDto(PlanningSummary s) {
        PlanningSummaryDto dto = new PlanningSummaryDto();
        dto.setTotalDistanceKm(s.totalDistanceKm());
        dto.setTotalElevationGain(s.totalElevationGain());
        dto.setBudgetKm(s.budgetKm());
        dto.setVerdict(PlanningSummaryDto.VerdictEnum.fromValue(s.verdict().name()));
        dto.setSurplusKm(s.surplusKm());
        dto.setPoolSize(s.poolSize());
        dto.setInitialPoolSize(s.initialPoolSize());
        if (s.baselineKm() != null) dto.setBaselineKm(s.baselineKm());
        if (s.roadAreas() != null) dto.setRoadAreas(s.roadAreas());
        dto.setClimbWarning(s.climbWarning());
        return dto;
    }

    public PlanningSessionDayDto toDayDto(PlanningSessionDay day) {
        PlanningSessionDayDto dto = new PlanningSessionDayDto();
        dto.setId(day.id());
        dto.setSessionId(day.sessionId());
        dto.setDayNumber(day.dayNumber());
        dto.setGeometryEncoded(Polyline3DCodec.encode(day.geometry()));
        dto.setWaypoints(day.waypoints().stream().map(this::toDto).toList());
        dto.setProfile(day.profile());
        dto.setEditedAt(day.editedAt());
        if (day.distanceKm() != null) dto.setDistanceKm(day.distanceKm());
        if (day.elevationGain() != null) dto.setElevationGain(day.elevationGain());
        if (day.elevationLoss() != null) dto.setElevationLoss(day.elevationLoss());
        dto.setStats(routeDraftExternalMapper.toStatsDto(day.stats()));
        return dto;
    }

    public WaypointDto toDto(Waypoint w) {
        WaypointDto dto = new WaypointDto();
        dto.setLng(w.lng());
        dto.setLat(w.lat());
        if (w.name() != null) dto.setName(w.name());
        return dto;
    }

    public Waypoint fromDto(WaypointDto dto) {
        if (dto == null) return null;
        return new Waypoint(dto.getLng(), dto.getLat(), dto.getName());
    }

    public RoutePreferencesDto toDto(RoutePreferences p) {
        if (p == null) return null;
        RoutePreferencesDto dto = new RoutePreferencesDto();
        if (p.countryIds() != null && !p.countryIds().isEmpty()) dto.setCountryIds(p.countryIds());
        if (p.levelIds() != null && !p.levelIds().isEmpty()) dto.setLevelIds(p.levelIds());
        if (p.specialGroupIds() != null && !p.specialGroupIds().isEmpty()) dto.setSpecialGroupIds(p.specialGroupIds());
        if (p.start() != null) dto.setStart(toDto(p.start()));
        if (p.end() != null) dto.setEnd(toDto(p.end()));
        if (p.via() != null && !p.via().isEmpty()) dto.setVia(p.via().stream().map(this::toDto).toList());
        if (p.loop() != null) dto.setLoop(p.loop());
        if (p.days() != null) dto.setDays(p.days());
        if (p.kmPerDay() != null) dto.setKmPerDay(p.kmPerDay());
        if (p.elevationPerDayM() != null) dto.setElevationPerDayM(p.elevationPerDayM());
        if (p.profile() != null) dto.setProfile(p.profile());
        return dto;
    }

    public RoutePreferences fromDto(RoutePreferencesDto dto) {
        if (dto == null) return RoutePreferences.empty();
        return new RoutePreferences(
                dto.getCountryIds(),
                dto.getLevelIds(),
                dto.getSpecialGroupIds(),
                fromDto(dto.getStart()),
                fromDto(dto.getEnd()),
                dto.getVia() != null ? dto.getVia().stream().map(this::fromDto).toList() : null,
                dto.getLoop(),
                dto.getDays(),
                dto.getKmPerDay(),
                dto.getElevationPerDayM(),
                dto.getProfile()
        );
    }

    public PlanTaskDto toTaskDto(PlanTask task) {
        PlanTaskDto dto = new PlanTaskDto();
        dto.setId(task.id());
        dto.setSessionId(task.sessionId());
        dto.setUserId(task.userId());
        dto.setStatus(PlanTaskStatusDto.fromValue(task.status().name()));
        dto.setStartedAt(task.startedAt());
        if (task.phase() != null) dto.setPhase(task.phase());
        if (task.progressCurrent() != null) dto.setProgressCurrent(task.progressCurrent());
        if (task.progressTotal() != null) dto.setProgressTotal(task.progressTotal());
        if (task.error() != null) dto.setError(task.error());
        if (task.completedAt() != null) dto.setCompletedAt(task.completedAt());
        return dto;
    }

    public PlanningIntentDto toDto(PlanningIntent intent) {
        return PlanningIntentDto.fromValue(intent.name());
    }

    public PlanningIntent fromDto(PlanningIntentDto dto) {
        return PlanningIntent.valueOf(dto.getValue());
    }
}
