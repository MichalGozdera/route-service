package velomarker.service.planning.coverage;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Współdzielone kolaboratory seeda (jeden komplet per plan), wstrzykiwane do klas odpowiedzialności
 * (Anchorer / GrowNear / SpurCutter / Trimmer). Czysta wiązka zależności — pozwala trzymać konstruktory
 * tych klas krótkie zamiast przepychać 8 osobnych argumentów.
 */
record SeedContext(EdgeRouter edgeRouter,
                   RouteMetrics metrics,
                   GminaIndex gminaIndex,
                   HilbertOrdering ordering,
                   List<UnvisitedArea> pool,
                   Map<String, Double> rewards,
                   CoverageDebug debug,
                   SeedOps ops,
                   boolean debugGeoJson,
                   Consumer<Boolean> snapToggle) {
}
