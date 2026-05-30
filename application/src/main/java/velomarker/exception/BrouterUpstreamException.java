package velomarker.exception;

import eu.cokeman.velomarker.exception.DomainException;

/**
 * Thrown when brouter returned an unexpected error (5xx, malformed response,
 * timeout). Maps to HTTP 502 Bad Gateway.
 */
public class BrouterUpstreamException extends DomainException {
    public BrouterUpstreamException(String message) {
        super(message);
    }

    public BrouterUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override public int httpStatus() { return 502; }
    @Override public String errorCode() { return "BROUTER_UPSTREAM_ERROR"; }
}
