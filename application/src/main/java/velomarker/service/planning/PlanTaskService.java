package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.PlanTask;
import velomarker.entity.planning.PlanTaskStatus;
import velomarker.entity.planning.PlanningSession;
import velomarker.port.in.planning.CalculatePlanUseCase;
import velomarker.port.out.planning.PlanTaskProgressPublisher;
import velomarker.port.out.planning.PlanTaskRepository;
import velomarker.port.out.planning.PlanningSessionRepository;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Lokalny tracker tasków planowania. Bez globalnego slotu — każdy user może mieć swój task RUNNING.
 * Każdy task = wirtualny wątek z {@code Executors.newVirtualThreadPerTaskExecutor()}.
 *
 * <p>Stan w {@code planning.plan_task} (persistent — przeżywa wyjście usera z mapy / restart serwera).
 * Po każdej zmianie phase/progress publikujemy event przez {@link PlanTaskProgressPublisher} —
 * implementacja AMQP → notification-service → SSE per-user.
 *
 * <p>Cancel = update statusu w DB + flag w {@link ComputationRegistry}; wątek liczący sprawdza
 * w checkpointach (po każdym dniu, po każdym brouter call) i rzuca TaskCancellationException.
 *
 * <p>Concurrency limit: brak globalnego slotu. Ograniczenie wynika z {@code Semaphore(8)} w BRouter
 * routing clientcie (route.calculate.max-concurrent). Virtual threads cierpliwie czekają na zasób.
 */
public class PlanTaskService implements PlanProgressSink, CalculatePlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlanTaskService.class);

    private final PlanTaskRepository taskRepository;
    private final PlanningSessionRepository sessionRepository;
    private final PlanningOrchestrationService orchestration;
    private final ComputationRegistry computationRegistry;
    private final PlanTaskProgressPublisher publisher;
    private final ExecutorService executor;

    public PlanTaskService(PlanTaskRepository taskRepository,
                           PlanningSessionRepository sessionRepository,
                           PlanningOrchestrationService orchestration,
                           ComputationRegistry computationRegistry,
                           PlanTaskProgressPublisher publisher,
                           ExecutorService executor) {
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
        this.orchestration = orchestration;
        this.computationRegistry = computationRegistry;
        this.publisher = publisher;
        this.executor = executor;
    }

    /**
     * Submit nowego taska. Tworzy wpis RUNNING w DB, odpala virtual thread.
     * Brak globalnego limitu — każdy user może mieć swój task.
     */
    public PlanTask submit(UUID userId, String bearerToken) {
        PlanningSession session = sessionRepository.findByUserId(userId).orElseThrow();
        PlanTask task = taskRepository.save(PlanTask.fresh(session.id(), userId));
        session.setLastTaskId(task.id());
        sessionRepository.save(session);
        publishSafely(task);

        Context reqCtx = Context.current();   // OTel context wątku HTTP (parent span) → propaguj do virtual thread
        executor.submit(() -> runTask(task.id(), userId, bearerToken, reqCtx));
        return task;
    }

    /** Aktywny lub ostatni task usera. */
    public Optional<PlanTask> latestForUser(UUID userId) {
        return taskRepository.findLatestForUser(userId);
    }

    public Optional<PlanTask> findById(UUID taskId) {
        return taskRepository.findById(taskId);
    }

    /** Sygnał anulowania per taskId — DB update + flag dla wątku liczącego. */
    public void cancelTask(UUID taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.status() == PlanTaskStatus.RUNNING) {
                computationRegistry.requestCancel(taskId);
            }
        });
    }

    // ===== CalculatePlanUseCase =====

    @Override
    public UUID calculate(UUID userId, String bearerToken) {
        return submit(userId, bearerToken).id();
    }

    @Override
    public void cancel(UUID userId) {
        latestForUser(userId).ifPresent(task -> {
            if (task.status() == PlanTaskStatus.RUNNING) {
                cancelTask(task.id());
            }
        });
    }

    // ===== updates wywoływane z PlanningOrchestrationService =====

    public void updatePhase(UUID taskId, String phase) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setPhase(phase);
            PlanTask saved = taskRepository.save(task);
            publishSafely(saved);
        });
    }

    public void updateProgress(UUID taskId, int current, Integer total) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setProgress(current, total);
            PlanTask saved = taskRepository.save(task);
            publishSafely(saved);
        });
    }

    public boolean isCancelRequested(UUID taskId) {
        return computationRegistry.isCancelRequested(taskId);
    }

    // ===== private =====

    private void runTask(UUID taskId, UUID userId, String bearerToken, Context reqCtx) {
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("route-service-plan");
        Span span = tracer.spanBuilder("plan").setParent(reqCtx)
                .setAttribute("task.id", taskId.toString())
                .setAttribute("user.id", userId.toString())
                .startSpan();
        computationRegistry.begin(taskId);
        try (Scope scope = span.makeCurrent()) {   // root span „plan" — fazy/BRouter/parallelMap dziedziczą parent
            orchestration.executePlan(taskId, userId, bearerToken);
            taskRepository.findById(taskId).ifPresent(task -> {
                if (task.status() == PlanTaskStatus.RUNNING) {
                    task.complete();
                    publishSafely(taskRepository.save(task));
                }
            });
        } catch (async.TaskCancellationException e) {
            log.info("Plan task {} cancelled", taskId);
            taskRepository.findById(taskId).ifPresent(task -> {
                task.cancel();
                publishSafely(taskRepository.save(task));
            });
        } catch (RuntimeException e) {
            log.error("Plan task " + taskId + " failed", e);
            span.setStatus(StatusCode.ERROR, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            taskRepository.findById(taskId).ifPresent(task -> {
                task.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                publishSafely(taskRepository.save(task));
            });
        } finally {
            computationRegistry.end(taskId);
            span.end();
        }
    }

    private void publishSafely(PlanTask task) {
        try {
            publisher.publish(task);
        } catch (RuntimeException e) {
            log.debug("Plan task progress publish failed (best-effort): {}", e.getMessage());
        }
    }
}
