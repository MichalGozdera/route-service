package velomarker.service.planning;

import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

// Lokalny tracker tasków planowania — każdy user może mieć swój task RUNNING na wirtualnym wątku.
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
        recoverOrphanedRunningTasks();
    }

    /**
     * Na starcie serwisu KAŻDY task RUNNING jest osierocony (świeży JVM nie liczy niczego) — JVM
     * padł w trakcie liczenia, więc `runTask` nie dokończył `finally`. Oznaczamy je FAILED, żeby
     * front (resumePolling) zobaczył stan terminalny i odblokował formularz zamiast wisieć na RUNNING.
     */
    private void recoverOrphanedRunningTasks() {
        try {
            List<PlanTask> orphaned = taskRepository.findRunning();
            for (PlanTask task : orphaned) {
                task.fail("Liczenie przerwane (restart serwisu)");
                publishSafely(taskRepository.save(task));
            }
            if (!orphaned.isEmpty()) {
                log.info("Plan task recovery: oznaczono {} osieroconych RUNNING jako FAILED", orphaned.size());
            }
        } catch (RuntimeException e) {
            log.warn("Plan task recovery failed (best-effort): {}", e.getMessage());
        }
    }

    public PlanTask submit(UUID userId, String bearerToken) {
        PlanningSession session = sessionRepository.findByUserId(userId).orElseThrow();
        PlanTask task = taskRepository.save(PlanTask.fresh(session.id(), userId));
        session.setLastTaskId(task.id());
        sessionRepository.save(session);
        publishSafely(task);

        Context reqCtx = Context.current();
        executor.submit(() -> runTask(task.id(), userId, bearerToken, reqCtx));
        return task;
    }

    public Optional<PlanTask> latestForUser(UUID userId) {
        return taskRepository.findLatestForUser(userId);
    }

    public Optional<PlanTask> findById(UUID taskId) {
        return taskRepository.findById(taskId);
    }

    public void cancelTask(UUID taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.status() != PlanTaskStatus.RUNNING) {
                return;
            }
            if (computationRegistry.isComputing(taskId)) {
                // Żywe liczenie w tym JVM — flaga, którą złapie wątek i zapisze CANCELLED.
                computationRegistry.requestCancel(taskId);
            } else {
                // Osierocony RUNNING (np. po restarcie) — nikt nie skonsumuje flagi, więc kończymy wprost.
                task.cancel();
                publishSafely(taskRepository.save(task));
            }
        });
    }

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

    public void updatePhase(UUID taskId, String phase) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setPhase(phase);
            PlanTask saved = taskRepository.save(task);
            publishSafely(saved);
        });
    }

    public boolean isCancelRequested(UUID taskId) {
        return computationRegistry.isCancelRequested(taskId);
    }

    private void runTask(UUID taskId, UUID userId, String bearerToken, Context reqCtx) {
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("route-service-plan");
        Span span = tracer.spanBuilder("plan").setParent(reqCtx)
                .setAttribute("task.id", taskId.toString())
                .setAttribute("user.id", userId.toString())
                .startSpan();
        computationRegistry.begin(taskId);
        try (Scope scope = span.makeCurrent()) {
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
        } catch (velomarker.exception.AllAreasVisitedException | velomarker.exception.AreasTooFarException e) {
            log.info("Plan task {}: oczekiwany wynik planowania ({})", new Object[]{taskId, e.getMessage()});
            taskRepository.findById(taskId).ifPresent(task -> {
                task.fail(e.getMessage());
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
