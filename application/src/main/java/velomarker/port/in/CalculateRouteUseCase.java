package velomarker.port.in;

import velomarker.entity.RouteCalculation;

import java.util.List;

public interface CalculateRouteUseCase {

    RouteCalculation calculate(CalculateRouteCommand command);

    record CalculateRouteCommand(
            List<double[]> waypoints,
            String profile
    ) {
    }
}
