package velomarker.exception;

/** Rzucane gdy visit-service jest nieosiągalne / zwraca błąd 5xx / parsowanie zawiodło. */
public class VisitServiceUnavailableException extends RuntimeException {

    public VisitServiceUnavailableException(String message) {
        super(message);
    }

    public VisitServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
