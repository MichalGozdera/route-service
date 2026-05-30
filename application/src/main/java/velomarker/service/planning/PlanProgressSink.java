package velomarker.service.planning;

import java.util.UUID;

/**
 * Callback dla PlanningOrchestrationService do raportowania postępu i sprawdzania cancel.
 * Implementacja: {@link PlanTaskService}. Wyodrębnione żeby uniknąć cyklicznej zależności
 * (PlanTaskService używa PlanningOrchestrationService, a Orchestration musi raportować postęp).
 */
public interface PlanProgressSink {

    void updatePhase(UUID taskId, String phase);

    void updateProgress(UUID taskId, int current, Integer total);

    boolean isCancelRequested(UUID taskId);
}
