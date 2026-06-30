package eu.cokeman.velomarker.out.coverage;

import org.springframework.stereotype.Component;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.AreaCoverageIndexFactory;

import java.util.List;

/** Buduje {@link JtsAreaCoverageIndex} nad pulą obszarów (PreparedGeometry + STRtree prekompiluje raz). */
@Component
public class JtsAreaCoverageIndexFactory implements AreaCoverageIndexFactory {

    @Override
    public AreaCoverageIndex build(List<UnvisitedArea> areas) {
        return new JtsAreaCoverageIndex(areas);
    }

    @Override
    public AreaCoverageIndex build(List<UnvisitedArea> areas, List<UnvisitedArea> adjacencyAreas) {
        return new JtsAreaCoverageIndex(areas, adjacencyAreas);
    }

    @Override
    public AreaCoverageIndex build(List<UnvisitedArea> areas, List<UnvisitedArea> adjacencyAreas,
                                   double creditDepthM, double deepDepthM) {
        return new JtsAreaCoverageIndex(areas, adjacencyAreas, creditDepthM, deepDepthM);
    }
}
