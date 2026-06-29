package velomarker.port.in.planning;

import velomarker.entity.planning.ManualSession;

import java.util.Optional;
import java.util.UUID;

/**
 * CRUD na ostatniej trasie manualnej użytkownika. JEDNA per user. Zapis robiony przez front
 * dopiero gdy user KOŃCZY planowanie (wyjście z trybu / zamknięcie strony) — nie per-punkt.
 */
public interface ManualSessionUseCase {

    /** Ostatnia trasa manualna usera; pusty Optional gdy nigdy nie zapisał. */
    Optional<ManualSession> get(UUID userId);

    /** Upsert — zapisuje/nadpisuje trasę manualną usera. Zwraca zapisany stan. */
    ManualSession save(ManualSession session);

    /** Usuwa trasę manualną (jawny „kosz" trasy na froncie). */
    void delete(UUID userId);
}
