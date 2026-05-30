package velomarker.port.out.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;

/**
 * Buduje {@link AreaCoverageIndex} nad konkretną pulą obszarów (per plan — pula = kandydaci po bbox).
 * Implementacja w adapterze (JTS) prekompiluje geometrię (PreparedGeometry + STRtree) raz przy budowie.
 */
public interface AreaCoverageIndexFactory {
    AreaCoverageIndex build(List<UnvisitedArea> areas);
}
