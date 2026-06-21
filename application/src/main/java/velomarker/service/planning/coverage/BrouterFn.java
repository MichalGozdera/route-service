package velomarker.service.planning.coverage;

import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.Waypoint;

import java.util.List;

/**
 * Routing BRouter dla plannera pokrycia. Jedna funkcja zamiast dwóch {@code BiFunction} — flaga
 * {@code computeStats} decyduje czy agregować statystyki trasy (surface/road/smoothness/spans).
 *
 * <p>Optymalizacja: ~10k wewnętrznych wywołań w seedzie/compact-loopie idzie z {@code false}
 * (potrzebny tylko dystans+geometria); statystyki liczy się DOKŁADNIE RAZ na końcu z {@code true}
 * (orchestrator potrzebuje spans do per-day slicing nawierzchni na froncie).
 */
@FunctionalInterface
public interface BrouterFn {
    RouteCalculation route(List<Waypoint> waypoints, String profile, boolean computeStats);
}
