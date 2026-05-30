package velomarker.exception;

import eu.cokeman.velomarker.exception.DomainException;

import java.util.UUID;

public class RouteDraftNotFoundException extends DomainException {
    public RouteDraftNotFoundException(UUID draftId) {
        super("Route draft not found: " + draftId);
    }
    @Override public int httpStatus() { return 404; }
    @Override public String errorCode() { return "ROUTE_DRAFT_NOT_FOUND"; }
}
