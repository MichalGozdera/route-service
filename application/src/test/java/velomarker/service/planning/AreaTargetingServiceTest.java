package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AreaTargetingServiceTest {

    private final AreaTargetingService svc = new AreaTargetingService();

    private static UnvisitedArea area(int id, double lng, double lat) {
        return UnvisitedArea.level(id, "A" + id, null, lat, lng, null, 1, 1, "gmina");
    }

    @Test
    void emptyPool_returnsEmptyList() {
        assertThat(svc.clusterIntoRoutes(List.of(), 3)).isEmpty();
    }

    @Test
    void onePool_oneRoute_returnsSingleCluster() {
        List<UnvisitedArea> pool = List.of(area(1, 14.0, 50.0), area(2, 14.5, 50.0));
        var clusters = svc.clusterIntoRoutes(pool, 1);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0)).hasSize(2);
    }

    @Test
    void poolFewerThanRoutes_capsAtPoolSize() {
        List<UnvisitedArea> pool = List.of(area(1, 14.0, 50.0), area(2, 14.5, 50.0));
        var clusters = svc.clusterIntoRoutes(pool, 5);
        assertThat(clusters).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void clusters_separateGeographicGroups() {
        // 3 grupy daleko od siebie po 5 obszarów każda.
        List<UnvisitedArea> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) pool.add(area(100 + i, 14.0 + i * 0.01, 50.0)); // CZ
        for (int i = 0; i < 5; i++) pool.add(area(200 + i, 19.0 + i * 0.01, 50.5)); // SK
        for (int i = 0; i < 5; i++) pool.add(area(300 + i, 23.0 + i * 0.01, 51.0)); // PL east
        var clusters = svc.clusterIntoRoutes(pool, 3);
        assertThat(clusters).hasSize(3);
        // Każdy klaster ma 5 elementów (grupy geograficzne).
        for (var c : clusters) {
            assertThat(c).hasSize(5);
        }
    }

    @Test
    void removesEmptyClusters() {
        // 2 obszary, 5 klastrów → 3 puste są usuwane.
        List<UnvisitedArea> pool = List.of(area(1, 14.0, 50.0), area(2, 19.0, 50.0));
        var clusters = svc.clusterIntoRoutes(pool, 5);
        for (var c : clusters) {
            assertThat(c).isNotEmpty();
        }
    }
}
