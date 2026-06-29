package velomarker.service.planning;

/**
 * Sink live-śladu związany z konkretnym taskiem (taskId/userId schowane w domknięciu).
 * Wpinany w {@code CoverageDebug.geometry(...)} — pojedynczy choke-point geometrii w seedzie.
 * Domyślnie no-op (planowanie bez live-podglądu działa normalnie).
 */
@FunctionalInterface
public interface PlanTraceSink {

    PlanTraceSink NOOP = frame -> { };

    void emit(PlanTraceFrame frame);
}
