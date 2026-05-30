package velomarker.exception;

import eu.cokeman.velomarker.exception.DomainException;

public class RouteDraftNameDuplicateException extends DomainException {
    public RouteDraftNameDuplicateException(String name) {
        super("Route draft name already in use: " + name);
    }
    @Override public int httpStatus() { return 409; }
    @Override public String errorCode() { return "ROUTE_DRAFT_NAME_DUPLICATE"; }
}
