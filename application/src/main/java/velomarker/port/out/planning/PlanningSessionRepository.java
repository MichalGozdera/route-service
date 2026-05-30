package velomarker.port.out.planning;

import velomarker.entity.planning.PlanningSession;

import java.util.Optional;
import java.util.UUID;

/** Repozytorium sesji asystenta. UNIQUE na user_id w DB — jedna aktywna sesja per user. */
public interface PlanningSessionRepository {

    Optional<PlanningSession> findByUserId(UUID userId);

    /** Upsert — insert jeśli nowa, update jeśli istnieje (po user_id). */
    PlanningSession save(PlanningSession session);

    /** Usuwa sesję (i CASCADE wszystkie powiązane dni). */
    void deleteByUserId(UUID userId);
}
