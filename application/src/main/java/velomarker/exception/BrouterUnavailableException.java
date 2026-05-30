package velomarker.exception;

import eu.cokeman.velomarker.exception.DomainException;

import java.util.Map;

/**
 * Thrown when brouter cannot serve a request right now (workers saturated,
 * semaphore timeout). Maps to HTTP 429 Too Many Requests with Retry-After.
 */
public class BrouterUnavailableException extends DomainException {
    private final int retryAfterSeconds;

    public BrouterUnavailableException(int retryAfterSeconds) {
        super("BRouter workers saturated, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override public int httpStatus() { return 429; }
    @Override public String errorCode() { return "BROUTER_UNAVAILABLE"; }

    @Override
    public Map<String, String> headers() {
        return Map.of("Retry-After", String.valueOf(retryAfterSeconds));
    }
}
