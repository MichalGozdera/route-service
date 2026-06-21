package velomarker.port.out;

import velomarker.entity.RouteCalculation;

import java.util.List;

public interface BrouterRoutingClient {

    /**
     * Sends a routing request to BRouter. Implementations are responsible for
     * concurrency control (semaphore) and translating upstream errors into the
     * domain exceptions: BrouterUnavailableException (rate-limited / busy) or
     * BrouterUpstreamException (5xx, parse failure, timeout).
     *
     * @param computeStats {@code true} = zbuduj {@link velomarker.entity.RouteStats} z messageList
     *        (parsing + per-segment spans) i zaloguj na INFO. {@code false} = skip — ważne dla
     *        wewnętrznych probing calls Coverage (~10k+ per multi-day coverage plan), gdzie stats
     *        ignorowane przez orchestrator, a logi by zalewały konsolę.
     */
    RouteCalculation calculate(List<double[]> waypoints, String profile, boolean computeStats);

    /** Default: backward-compat — zawsze buduje stats. */
    default RouteCalculation calculate(List<double[]> waypoints, String profile) {
        return calculate(waypoints, profile, true);
    }

    /**
     * v3.16: zeruje liczniki „BRouter cumulative" per plan coverage — żeby log pokazywał strzały
     * TEGO planu, nie skumulowane od startu serwisu (uwaga usera: calls=3600 nie zerował się).
     * Default no-op (klienci bez liczników, np. HTTP, ignorują).
     */
    default void resetPlanCounters() { }

    /**
     * Diagnostyka snapu waypointów (Instrument A): gdy włączone, klient loguje dla każdego routowanego
     * waypointa „nasza propozycja → punkt po dopasowaniu (crosspoint) + offset". Włączane WYŁĄCZNIE na czas
     * fazy anchor (przez {@code Anchorer}), żeby log nie leciał na ~10k calli seeda ani na finalnym recompute.
     * Default no-op.
     */
    default void setSnapLogging(boolean enabled) { }
}
