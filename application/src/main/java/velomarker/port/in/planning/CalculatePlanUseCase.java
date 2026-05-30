package velomarker.port.in.planning;

import java.util.UUID;

/**
 * Uruchamia liczenie wyprawy w trybie async-task (PLAN_ROUTE). Zwraca databaseId taska z
 * common-application/async — front polluje przez SSE notification-service jak importy GPX.
 */
public interface CalculatePlanUseCase {

    /**
     * Submit async task PLAN_ROUTE dla aktualnej sesji usera.
     *
     * @return databaseId taska (do śledzenia przez /async-tasks/{id} i SSE)
     * @throws velomarker.exception.PlanningSessionNotReadyException gdy preferences nie mają minimum dla intentu
     * @throws velomarker.exception.PlanningSessionMissingException gdy nie ma sesji albo intent=null
     */
    UUID calculate(UUID userId, String bearerToken);

    /** Sygnalizuje anulowanie aktywnego liczenia. No-op jeśli brak aktywnego taska. */
    void cancel(UUID userId);
}
