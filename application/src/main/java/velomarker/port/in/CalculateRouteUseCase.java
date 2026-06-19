package velomarker.port.in;

import velomarker.entity.RouteCalculation;

import java.util.List;

public interface CalculateRouteUseCase {

    RouteCalculation calculate(CalculateRouteCommand command);

    /**
     * v3.17: zeruje liczniki diagnostyczne per plan coverage (np. „Elevation enrichment timing
     * last 200 of N") — żeby N liczyło TEN plan, nie skumulowane od startu serwisu. Default no-op.
     */
    default void resetPlanCounters() { }

    /**
     * @param waypoints lista par {@code [lng, lat]}, min. 2
     * @param profile nazwa profilu BRoutera (np. "trekking", "fastbike")
     * @param computeStats {@code true} = backend zbuduje {@link velomarker.entity.RouteStats}
     *        (parsing messageList + per-segment spans) i zaloguje na INFO. {@code false} = skip
     *        (oszczędza CPU + log spam dla wewnętrznych probing calls ALNS2 — typowo 10k+ calls
     *        per multi-day coverage plan, których stats nikt nie używa).
     *        Default {@code true} dla manual routing z frontu i finalnych chunków planning'u.
     */
    record CalculateRouteCommand(
            List<double[]> waypoints,
            String profile,
            boolean computeStats
    ) {
        public CalculateRouteCommand(List<double[]> waypoints, String profile) {
            this(waypoints, profile, true);
        }
    }
}
