package velomarker.port.out.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;
import java.util.Set;

/**
 * Spatial oracle dla ZALICZANIA obszarów przez trasę — liczone na PEŁNEJ geometrii (JTS w adapterze),
 * nie na ręcznym ray-castingu po downsamplowanych ringach. Eliminuje false-positives typu „Gorzów
 * Śląski" (ślad smyra po zewnętrznej stronie meandrującej granicy, ale jej nie przekracza).
 *
 * <p>Implementacja (adapter): JTS {@code PreparedGeometry} + {@code STRtree}. Kryterium głębokości
 * (trasa musi wjechać realnie w głąb, nie otrzeć krawędzi) = {@code area.buffer(-depth).intersects(line)}.
 */
public interface AreaCoverageIndex {

    /** Id obszarów zaliczonych przez trasę (depth-aware: wjazd w głąb, nie otarcie krawędzi). */
    Set<Integer> visitedAreaIds(List<double[]> routeGeometry);

    /** Najmniejszy powierzchniowo obszar zawierający punkt (obwarzanek: miasto w dziurze wiejskiej),
     *  lub null gdy punkt poza wszystkimi obszarami. */
    UnvisitedArea findAreaForPoint(double lng, double lat);
}
