package velomarker.service.planning;

import java.util.List;
import java.util.Map;

/**
 * Klatka live-podglądu planowania (jeden checkpoint): geometria całej trasy + bieżące metryki.
 * {@code coveredAreaIds} = obszary zgarnięte (≥200m kredyt) — front koloruje je po ID i pokazuje
 * liczbę w stoperze. {@code coveredByLevel} = rozbicie na rodzaje ({@code nazwa rodzaju → liczba},
 * np. „Powiat"→12) — stoper pokazuje typy. Rosną/maleją między klatkami (peel odbarwia).
 */
public record PlanTraceFrame(
        String phase,
        List<double[]> geometry,
        double distanceKm,
        int elevationGainM,
        List<Integer> coveredAreaIds,
        Map<String, Integer> coveredByLevel
) {
}
