package velomarker.port.out.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;

/**
 * Buduje {@link AreaCoverageIndex} nad konkretną pulą obszarów (per plan — pula = kandydaci po bbox).
 * Implementacja w adapterze (JTS) prekompiluje geometrię (PreparedGeometry + STRtree) raz przy budowie.
 */
public interface AreaCoverageIndexFactory {
    AreaCoverageIndex build(List<UnvisitedArea> areas);

    /**
     * Jak {@link #build(List)}, ale dodatkowo wstawia {@code adjacencyAreas} (historycznie zaliczone) do
     * grafu sąsiedztwa i indeksu przestrzennego — by domykać dziury i zgrywać trasę z dawnym pokryciem.
     * Te obszary NIE liczą się jako zaliczenia trasy (są wykluczone z {@code visitedAreaIds}/{@code touched}/
     * {@code findAreaForPoint} itp.) — wchodzą wyłącznie w adjacency.
     */
    AreaCoverageIndex build(List<UnvisitedArea> areas, List<UnvisitedArea> adjacencyAreas);
}
