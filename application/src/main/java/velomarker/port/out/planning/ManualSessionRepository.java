package velomarker.port.out.planning;

import velomarker.entity.planning.ManualSession;

import java.util.Optional;
import java.util.UUID;

/** Repozytorium ostatniej trasy manualnej. UNIQUE na user_id w DB — jedna per user. */
public interface ManualSessionRepository {

    Optional<ManualSession> findByUserId(UUID userId);

    /** Upsert — insert jeśli nowa, update jeśli istnieje (po user_id). */
    ManualSession save(ManualSession session);

    void deleteByUserId(UUID userId);
}
