package velomarker.service.planning.coverage.debug;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Diagnostyka „dziur" pokrycia: ile niezaliczonych gmin i jak daleko od trasy (loguje zawsze, nie zależy od debugGeoJson). */
public final class HoleDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(HoleDiagnostics.class);

    private HoleDiagnostics() {}

    public static void log(RouteCalculation bestCalc, List<UnvisitedArea> candidatePool,
                           CoverageAreaIndex coverageAreaIndex, Set<Integer> bestVisited) {
        List<double[]> diagGeom = GeometryUtil.subsampleGeometry(bestCalc.coordinates(), 4000);
        int h0_3 = 0, h3_6 = 0, h6_15 = 0, h15_plus = 0, totalHoles = 0;
        List<String> holeNames = new ArrayList<>();
        for (UnvisitedArea a : candidatePool) {
            if (bestVisited.contains(a.areaId())) continue;
            totalHoles++;
            double d = coverageAreaIndex.distToRoute(a, diagGeom);
            if (d <= 3.0) h0_3++;
            else if (d <= 6.0) h3_6++;
            else if (d <= 15.0) h6_15++;
            else h15_plus++;
            if (holeNames.size() < 60) holeNames.add(a.name() + "(" + Math.round(d) + "km)");
        }
        log.info("Coverage hole diagnostics: {} dziur / {} pool → distToRoute 0-3km:{}, 3-6km:{}, 6-15km:{}, >15km:{}",
                new Object[]{totalHoles, candidatePool.size(), h0_3, h3_6, h6_15, h15_plus});
        log.info("Coverage hole names (≤60): {}", String.join(", ", holeNames));
    }
}
