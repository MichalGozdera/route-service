package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.mapper.PlanningExternalMapper;
import eu.cokeman.velomarker.openapi.api.PlanningApi;
import eu.cokeman.velomarker.openapi.model.CalculatePlanResponseDto;
import eu.cokeman.velomarker.openapi.model.PlanTaskDto;
import eu.cokeman.velomarker.openapi.model.PlanningSessionDayDto;
import eu.cokeman.velomarker.openapi.model.PlanningSessionResponseDto;
import eu.cokeman.velomarker.openapi.model.RoutePreferencesDto;
import eu.cokeman.velomarker.openapi.model.SaveAsExpeditionRequestDto;
import eu.cokeman.velomarker.openapi.model.SaveAsExpeditionResponseDto;
import eu.cokeman.velomarker.openapi.model.SetPlanningIntentRequestDto;
import eu.cokeman.velomarker.openapi.model.UpdatePlanningDayRequestDto;
import eu.cokeman.velomarker.security.UserContextHelper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.Waypoint;
import velomarker.entity.planning.PlanTask;
import velomarker.port.in.planning.CalculatePlanUseCase;
import velomarker.port.in.planning.PlanningSessionUseCase;
import velomarker.port.in.planning.SavePlanAsExpeditionUseCase;
import velomarker.port.in.planning.UpdatePlanningDayUseCase;
import velomarker.port.out.planning.PlanTaskRepository;
import velomarker.port.out.planning.PlanningSessionDayRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
public class PlanningController implements PlanningApi {

    private final PlanningSessionUseCase sessionUseCase;
    private final CalculatePlanUseCase calculateUseCase;
    private final UpdatePlanningDayUseCase updateDayUseCase;
    private final SavePlanAsExpeditionUseCase saveAsExpeditionUseCase;
    private final PlanningSessionDayRepository dayRepository;
    private final PlanTaskRepository taskRepository;
    private final PlanningExternalMapper mapper;
    private final UserContextHelper userContext;

    public PlanningController(PlanningSessionUseCase sessionUseCase,
                              CalculatePlanUseCase calculateUseCase,
                              UpdatePlanningDayUseCase updateDayUseCase,
                              SavePlanAsExpeditionUseCase saveAsExpeditionUseCase,
                              PlanningSessionDayRepository dayRepository,
                              PlanTaskRepository taskRepository,
                              PlanningExternalMapper mapper,
                              UserContextHelper userContext) {
        this.sessionUseCase = sessionUseCase;
        this.calculateUseCase = calculateUseCase;
        this.updateDayUseCase = updateDayUseCase;
        this.saveAsExpeditionUseCase = saveAsExpeditionUseCase;
        this.dayRepository = dayRepository;
        this.taskRepository = taskRepository;
        this.mapper = mapper;
        this.userContext = userContext;
    }

    @Override
    public ResponseEntity<PlanningSessionResponseDto> getPlanningSession() {
        UUID userId = userContext.getCurrentUserId();
        Optional<PlanningSession> session = sessionUseCase.getSession(userId);
        if (session.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        List<PlanningSessionDay> days = dayRepository.findBySessionId(session.get().id());
        return ResponseEntity.ok(mapper.toSessionResponse(session.get(), days));
    }

    @Override
    public ResponseEntity<PlanningSessionResponseDto> setPlanningIntent(SetPlanningIntentRequestDto req) {
        UUID userId = userContext.getCurrentUserId();
        PlanningSession session = sessionUseCase.setIntent(userId, mapper.fromDto(req.getIntent()));
        List<PlanningSessionDay> days = dayRepository.findBySessionId(session.id());
        return ResponseEntity.ok(mapper.toSessionResponse(session, days));
    }

    @Override
    public ResponseEntity<PlanningSessionResponseDto> updatePlanningForm(RoutePreferencesDto dto) {
        UUID userId = userContext.getCurrentUserId();
        PlanningSession session = sessionUseCase.updateForm(userId, mapper.fromDto(dto));
        List<PlanningSessionDay> days = dayRepository.findBySessionId(session.id());
        return ResponseEntity.ok(mapper.toSessionResponse(session, days));
    }

    @Override
    public ResponseEntity<Void> resetPlanningSession() {
        UUID userId = userContext.getCurrentUserId();
        sessionUseCase.reset(userId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CalculatePlanResponseDto> calculatePlan() {
        UUID userId = userContext.getCurrentUserId();
        String bearerToken = extractBearerToken();
        UUID taskId = calculateUseCase.calculate(userId, bearerToken);
        CalculatePlanResponseDto dto = new CalculatePlanResponseDto();
        dto.setTaskId(taskId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(dto);
    }

    @Override
    public ResponseEntity<Void> cancelPlan() {
        UUID userId = userContext.getCurrentUserId();
        calculateUseCase.cancel(userId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<PlanningSessionDayDto> updatePlanningDay(Integer dayNumber, UpdatePlanningDayRequestDto req) {
        UUID userId = userContext.getCurrentUserId();
        List<Waypoint> waypoints = req.getWaypoints().stream().map(mapper::fromDto).toList();
        PlanningSessionDay updated = updateDayUseCase.updateDay(userId, dayNumber, waypoints);
        return ResponseEntity.ok(mapper.toDayDto(updated));
    }

    @Override
    public ResponseEntity<PlanTaskDto> getPlanningTask(UUID taskId) {
        UUID userId = userContext.getCurrentUserId();
        Optional<PlanTask> task = taskRepository.findById(taskId);
        if (task.isEmpty() || !task.get().userId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(mapper.toTaskDto(task.get()));
    }

    @Override
    public ResponseEntity<SaveAsExpeditionResponseDto> savePlanAsExpedition(SaveAsExpeditionRequestDto req) {
        UUID userId = userContext.getCurrentUserId();
        var result = saveAsExpeditionUseCase.saveAsExpedition(userId, req.getGroupName());
        SaveAsExpeditionResponseDto dto = new SaveAsExpeditionResponseDto();
        dto.setGroupId(result.groupId());
        dto.setDraftIds(result.draftIds());
        return ResponseEntity.ok(dto);
    }

    /** Wyciąga raw JWT token z SecurityContext do propagacji do visit-service. */
    private String extractBearerToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return "Bearer " + jwt.getTokenValue();
        }
        throw new IllegalStateException("No JWT in SecurityContext");
    }
}
