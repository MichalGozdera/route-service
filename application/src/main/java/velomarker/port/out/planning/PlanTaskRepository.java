package velomarker.port.out.planning;

import velomarker.entity.planning.PlanTask;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repozytorium tasków planowania. CASCADE DELETE z planning.session. */
public interface PlanTaskRepository {

    PlanTask save(PlanTask task);

    Optional<PlanTask> findById(UUID taskId);

    /** Aktualny task usera (najnowszy non-terminal lub ostatni jakikolwiek). */
    Optional<PlanTask> findLatestForUser(UUID userId);

    /** Wszystkie taski w stanie RUNNING — na starcie serwisu to osierocone (do recovery). */
    List<PlanTask> findRunning();

    void deleteBySessionId(UUID sessionId);
}
