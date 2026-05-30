package velomarker.exception;

import java.util.UUID;

/** Rzucane gdy próba operacji na sesji asystenta, która nie istnieje (lub intent=null). */
public class PlanningSessionMissingException extends RuntimeException {

    public PlanningSessionMissingException(UUID userId) {
        super("No active planning session for user " + userId);
    }

    public PlanningSessionMissingException(String message) {
        super(message);
    }
}
