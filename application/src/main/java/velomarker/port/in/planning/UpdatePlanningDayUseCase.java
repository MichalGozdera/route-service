package velomarker.port.in.planning;

import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.Waypoint;

import java.util.List;
import java.util.UUID;

/**
 * Zapis edycji pojedynczego dnia (po przesunięciu/dodaniu waypointów na mapie). Re-routuje przez
 * BRouter + re-samplowanie elevation. Zwraca zaktualizowany dzień z nową geometrią i metrykami.
 */
public interface UpdatePlanningDayUseCase {

    /**
     * Re-route dnia z nowymi waypointami.
     *
     * @return zaktualizowany dzień (geometria + metryki)
     */
    PlanningSessionDay updateDay(UUID userId, int dayNumber, List<Waypoint> newWaypoints);
}
