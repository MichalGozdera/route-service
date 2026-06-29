package velomarker.service.planning.coverage.seed;

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

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Współdzielone kolaboratory seeda (jeden komplet per plan) wstrzykiwane do klas odpowiedzialności. */
public record SeedContext(EdgeRouter edgeRouter,
                   RouteMetrics metrics,
                   CoverageAreaIndex coverageAreaIndex,
                   HilbertOrdering ordering,
                   List<UnvisitedArea> pool,
                   Map<String, Double> rewards,
                   CoverageDebug debug,
                   SeedOps ops,
                   boolean debugGeoJson,
                   Consumer<Boolean> snapToggle) {
}
