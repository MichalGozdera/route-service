package velomarker.port.out.planning;

import velomarker.entity.planning.PlanTask;

/**
 * Port publikacji eventów postępu PlanTask. Implementacja w adapter:
 * - AmqpPlanTaskProgressPublisher: publish do notification-exchange, notification-service routuje na SSE per-user.
 * - Default no-op (gdy AMQP infra niedostępne lokalnie).
 */
public interface PlanTaskProgressPublisher {

    /** Wołane przy każdej zmianie phase/progress/status. Best-effort — nie blokuje obliczeń jeśli upadnie. */
    void publish(PlanTask task);
}
