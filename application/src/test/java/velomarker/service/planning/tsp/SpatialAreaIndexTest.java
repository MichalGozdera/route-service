package velomarker.service.planning.tsp;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.service.planning.PlanningOrchestrationService;
import velomarker.service.planning.PlanningOrchestrationService.AreaCandidate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test pokrywa kluczowe inwarianty SpatialAreaIndex używanego przez TSP cheapest insertion:
 * - query nearby zwraca tylko bliskie obszary (filtracja po cell)
 * - query along edge filtruje po bbox edge + buffer
 * - markPicked wyklucza obszar z kolejnych zapytań
 * - corner case: obszar na granicy cell jest zindeksowany w prawidlowej cell
 */
class SpatialAreaIndexTest {

    /** Helper: stworz AreaCandidate przy danej pozycji z dummy ring. */
    private static AreaCandidate areaAt(int id, double lng, double lat) {
        double[][] ring = {
                {lng - 0.01, lat - 0.01},
                {lng + 0.01, lat - 0.01},
                {lng + 0.01, lat + 0.01},
                {lng - 0.01, lat + 0.01}
        };
        UnvisitedArea ua = UnvisitedArea.level(id, "Area" + id, null, lat, lng, ring, 1, 1, "test");
        return new AreaCandidate(ua, false, 0, 0, 0, lng, lat);
    }

    @Test
    void queryNearby_zwracaTylkoBliskie() {
        SpatialAreaIndex idx = new SpatialAreaIndex(0.1);
        idx.addAll(List.of(
                areaAt(1, 15.0, 50.0),   // blisko query point
                areaAt(2, 15.05, 50.05), // blisko query point
                areaAt(3, 16.0, 50.0),   // ~80km na wschod -- daleko
                areaAt(4, 15.0, 51.0)    // ~110km na polnoc -- daleko
        ));
        // Query punkt (15.0, 50.0), radius 10km
        List<AreaCandidate> result = idx.queryNearby(15.0, 50.0, 10.0);
        assertThat(result).extracting(c -> c.getArea().areaId())
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void queryAlongEdge_zwracaObszaryWBboxEdge() {
        SpatialAreaIndex idx = new SpatialAreaIndex(0.1);
        idx.addAll(List.of(
                areaAt(1, 15.5, 50.0),   // na trasie 15->16
                areaAt(2, 15.5, 50.1),   // ~11km od trasy 15->16 (lat=50)
                areaAt(3, 15.0, 51.0),   // ~110km od trasy -- daleko
                areaAt(4, 16.5, 50.0)    // poza edge end
        ));
        // Edge od (15.0, 50.0) do (16.0, 50.0), buffer 20km
        List<AreaCandidate> result = idx.queryAlongEdge(
                new double[]{15.0, 50.0}, new double[]{16.0, 50.0}, 20.0);
        // area #4 (16.5, 50.0) jest ~36 km od edge end (poza buffer 20km) -- wykluczona
        assertThat(result).extracting(c -> c.getArea().areaId())
                .contains(1, 2)
                .doesNotContain(3, 4);
    }

    @Test
    void markPicked_wykluczaZNastepnychQueries() {
        SpatialAreaIndex idx = new SpatialAreaIndex(0.1);
        AreaCandidate a1 = areaAt(1, 15.0, 50.0);
        AreaCandidate a2 = areaAt(2, 15.05, 50.0);
        idx.addAll(List.of(a1, a2));

        List<AreaCandidate> before = idx.queryNearby(15.0, 50.0, 10.0);
        assertThat(before).hasSize(2);

        idx.markPicked(a1);
        List<AreaCandidate> after = idx.queryNearby(15.0, 50.0, 10.0);
        assertThat(after).extracting(c -> c.getArea().areaId()).containsExactly(2);
    }

    @Test
    void distancePointToSegmentKm_punktNaProstej_returns0() {
        // Punkt (15.5, 50.0) leży DOKLADNIE na linii (15.0, 50.0)→(16.0, 50.0)
        double d = SpatialAreaIndex.distancePointToSegmentKm(
                15.5, 50.0,
                15.0, 50.0,
                16.0, 50.0);
        assertThat(d).isLessThan(0.1); // ~0 km
    }

    @Test
    void distancePointToSegmentKm_punktProstoOdSrodka_returnsRoughly11km() {
        // 0.1° na N od środka edge = ~11.1 km
        double d = SpatialAreaIndex.distancePointToSegmentKm(
                15.5, 50.1,
                15.0, 50.0,
                16.0, 50.0);
        assertThat(d).isBetween(10.0, 12.0);
    }

    @Test
    void emptyIndex_queryZwracaPustaListe() {
        SpatialAreaIndex idx = new SpatialAreaIndex();
        assertThat(idx.queryNearby(15.0, 50.0, 100.0)).isEmpty();
        assertThat(idx.queryAlongEdge(new double[]{15, 50}, new double[]{16, 50}, 100.0)).isEmpty();
        assertThat(idx.totalSize()).isZero();
    }

    @Test
    void totalSize_liczyDodaneAreas() {
        SpatialAreaIndex idx = new SpatialAreaIndex(0.1);
        idx.addAll(List.of(
                areaAt(1, 15.0, 50.0),
                areaAt(2, 15.05, 50.0),
                areaAt(3, 16.0, 50.0)
        ));
        assertThat(idx.totalSize()).isEqualTo(3);
        assertThat(idx.pickedCount()).isZero();
    }
}
