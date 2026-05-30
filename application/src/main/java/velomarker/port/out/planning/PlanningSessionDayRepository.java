package velomarker.port.out.planning;

import velomarker.entity.planning.PlanningSessionDay;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium policzonych dni sesji. Klucz unikalny: (session_id, day_number).
 * CASCADE DELETE z planning.session — usunięcie sesji kasuje wszystkie dni.
 */
public interface PlanningSessionDayRepository {

    List<PlanningSessionDay> findBySessionId(UUID sessionId);

    Optional<PlanningSessionDay> findBySessionIdAndDayNumber(UUID sessionId, int dayNumber);

    PlanningSessionDay save(PlanningSessionDay day);

    /** Batch insert/replace dla wyniku liczenia (transakcyjnie zastępuje wszystkie dni sesji). */
    void replaceAll(UUID sessionId, List<PlanningSessionDay> days);

    void deleteBySessionId(UUID sessionId);
}
