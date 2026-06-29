package velomarker.port.out.planning;

import velomarker.service.planning.PlanTraceFrame;

import java.util.UUID;

/**
 * Port publikacji LIVE śladu w trakcie planowania (COVERAGE) — aktualna realna geometria całej trasy
 * + metryki (km, przewyższenie, zgarnięte obszary) w kluczowych momentach (baseline, co N batchy,
 * per-runda kotwiczenia/cięcia, finalize). Implementacja w adapter: AmqpPlanTracePublisher →
 * notification-exchange → SSE „planning-track". Default no-op. Best-effort — nie blokuje obliczeń.
 */
public interface PlanTracePublisher {

    void publish(UUID taskId, UUID userId, PlanTraceFrame frame);
}
