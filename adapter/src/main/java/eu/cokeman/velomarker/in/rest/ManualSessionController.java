package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.mapper.ManualSessionExternalMapper;
import eu.cokeman.velomarker.openapi.api.ManualApi;
import eu.cokeman.velomarker.openapi.model.ManualSessionResponseDto;
import eu.cokeman.velomarker.openapi.model.ManualSessionUpsertRequestDto;
import eu.cokeman.velomarker.security.UserContextHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.planning.ManualSession;
import velomarker.port.in.planning.ManualSessionUseCase;

import java.util.Optional;
import java.util.UUID;

@RestController
public class ManualSessionController implements ManualApi {

    private final ManualSessionUseCase useCase;
    private final ManualSessionExternalMapper mapper;
    private final UserContextHelper userContext;

    public ManualSessionController(ManualSessionUseCase useCase,
                                   ManualSessionExternalMapper mapper,
                                   UserContextHelper userContext) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.userContext = userContext;
    }

    @Override
    public ResponseEntity<ManualSessionResponseDto> getManualSession() {
        UUID userId = userContext.getCurrentUserId();
        Optional<ManualSession> session = useCase.get(userId);
        return session.map(s -> ResponseEntity.ok(mapper.toResponse(s)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Override
    public ResponseEntity<ManualSessionResponseDto> upsertManualSession(ManualSessionUpsertRequestDto req) {
        UUID userId = userContext.getCurrentUserId();
        ManualSession saved = useCase.save(mapper.fromUpsert(userId, req));
        return ResponseEntity.ok(mapper.toResponse(saved));
    }

    @Override
    public ResponseEntity<Void> deleteManualSession() {
        UUID userId = userContext.getCurrentUserId();
        useCase.delete(userId);
        return ResponseEntity.noContent().build();
    }
}
