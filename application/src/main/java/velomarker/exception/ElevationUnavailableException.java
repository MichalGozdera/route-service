package velomarker.exception;

import eu.cokeman.velomarker.exception.DomainException;

public class ElevationUnavailableException extends DomainException {
    public ElevationUnavailableException(String message) {
        super(message);
    }

    public ElevationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override public int httpStatus() { return 502; }
    @Override public String errorCode() { return "ELEVATION_UNAVAILABLE"; }
}
