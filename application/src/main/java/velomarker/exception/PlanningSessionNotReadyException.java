package velomarker.exception;

/**
 * Rzucane gdy formularz nie ma minimum potrzebnego do uruchomienia liczenia
 * (np. brak start/end dla intentu AB albo brak countries/levels dla COVERAGE).
 */
public class PlanningSessionNotReadyException extends RuntimeException {

    public PlanningSessionNotReadyException(String missingFields) {
        super("Cannot calculate plan — missing fields: " + missingFields);
    }
}
