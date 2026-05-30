package velomarker.entity.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * Task obliczania trasy. Prosty tracking (lokalny, bez common-application/async).
 * Brak globalnego slotu — każdy user może mieć swój task RUNNING, ograniczenie wynika tylko
 * z brouter Semaphore(8) na poziomie route-service.
 *
 * <p>Frontend polluje {@code GET /planning/task/{id}} albo subskrybuje SSE
 * {@code GET /planning/task/{id}/events} (event-stream bezpośrednio z route-service —
 * bez notification-service po drodze).
 */
public final class PlanTask {

    private final UUID id;
    private final UUID sessionId;
    private final UUID userId;
    private PlanTaskStatus status;
    private String phase;
    private Integer progressCurrent;
    private Integer progressTotal;
    private String error;
    private final Instant startedAt;
    private Instant completedAt;

    public PlanTask(UUID id, UUID sessionId, UUID userId, PlanTaskStatus status, String phase,
                    Integer progressCurrent, Integer progressTotal, String error,
                    Instant startedAt, Instant completedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.status = status;
        this.phase = phase;
        this.progressCurrent = progressCurrent;
        this.progressTotal = progressTotal;
        this.error = error;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public static PlanTask fresh(UUID sessionId, UUID userId) {
        return new PlanTask(UUID.randomUUID(), sessionId, userId, PlanTaskStatus.RUNNING,
                "starting", 0, null, null, Instant.now(), null);
    }

    public void setPhase(String newPhase) { this.phase = newPhase; }
    public void setProgress(int current, Integer total) {
        this.progressCurrent = current;
        if (total != null) this.progressTotal = total;
    }

    public void complete() {
        this.status = PlanTaskStatus.COMPLETED;
        this.phase = "done";
        this.completedAt = Instant.now();
    }

    public void fail(String errorMessage) {
        this.status = PlanTaskStatus.FAILED;
        this.error = errorMessage;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        this.status = PlanTaskStatus.CANCELLED;
        this.completedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID sessionId() { return sessionId; }
    public UUID userId() { return userId; }
    public PlanTaskStatus status() { return status; }
    public String phase() { return phase; }
    public Integer progressCurrent() { return progressCurrent; }
    public Integer progressTotal() { return progressTotal; }
    public String error() { return error; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
}
