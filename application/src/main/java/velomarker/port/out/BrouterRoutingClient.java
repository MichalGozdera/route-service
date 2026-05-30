package velomarker.port.out;

import velomarker.entity.RouteCalculation;

import java.util.List;

public interface BrouterRoutingClient {

    /**
     * Sends a routing request to BRouter. Implementations are responsible for
     * concurrency control (semaphore) and translating upstream errors into the
     * domain exceptions: BrouterUnavailableException (rate-limited / busy) or
     * BrouterUpstreamException (5xx, parse failure, timeout).
     */
    RouteCalculation calculate(List<double[]> waypoints, String profile);
}
