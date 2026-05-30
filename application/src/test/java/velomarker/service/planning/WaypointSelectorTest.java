package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WaypointSelectorTest {

    private final WaypointSelector selector = new WaypointSelector();

    private static UnvisitedArea area(int id, double lng, double lat) {
        return UnvisitedArea.level(id, "A" + id, null, lat, lng, null, 1, 1, "gmina");
    }

    /** Mała kwadratowa gmina wokół punktu (lng, lat) o boku ~5 km. */
    private static UnvisitedArea ringArea(int id, double lng, double lat) {
        double d = 0.025; // ~2.5 km
        double[][] ring = {
                {lng - d, lat - d}, {lng + d, lat - d},
                {lng + d, lat + d}, {lng - d, lat + d}
        };
        return new UnvisitedArea(id, "A" + id, null, lat, lng, ring, 1, 1, "gmina", null);
    }

    @Test
    void orderAreas_small_returnsAsIs() {
        List<UnvisitedArea> a = List.of(area(1, 14.4, 50.0), area(2, 14.5, 50.0));
        var result = selector.orderAreas(a, new double[]{14.0, 50.0}, new double[]{15.0, 50.0});
        assertThat(result).hasSize(2);
    }

    @Test
    void orderAreas_alongAxis_isMonotonic() {
        // 5 obszarów wzdłuż osi (14.0, 50.0) → (15.0, 50.0). Powinny zostać posortowane od start do end.
        List<UnvisitedArea> areas = new ArrayList<>();
        // Wymieszane kolejność wejściowa.
        areas.add(area(3, 14.6, 50.0));
        areas.add(area(1, 14.2, 50.0));
        areas.add(area(5, 14.9, 50.0));
        areas.add(area(2, 14.4, 50.0));
        areas.add(area(4, 14.75, 50.0));
        var ordered = selector.orderAreas(areas, new double[]{14.0, 50.0}, new double[]{15.0, 50.0});
        // Po order: monotonic increase in lng
        for (int i = 1; i < ordered.size(); i++) {
            assertThat(ordered.get(i).lng()).isGreaterThan(ordered.get(i - 1).lng());
        }
    }

    @Test
    void orderAreas_cancelRequested_returnsEarly() {
        // Pula 100 obszarów, cancel po pierwszym pass'ie. Mierzymy że NIE leci 60 passów × 100² = 600k operacji.
        List<UnvisitedArea> areas = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            areas.add(area(i, 14.0 + i * 0.01, 50.0 + (i % 3) * 0.01));
        }
        long start = System.nanoTime();
        var result = selector.orderAreas(areas, new double[]{14.0, 50.0}, new double[]{15.0, 50.5}, () -> true);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).hasSize(100);
        assertThat(elapsedMs).isLessThan(500); // bardzo szybko — cancel ucina pętle
    }

    @Test
    void straightLineDistanceKm_sumsHaversines() {
        // (14, 50) → (14, 51) ~ 111 km, (14, 51) → (15, 51) ~ 70 km → suma ~181 km
        double d = selector.straightLineDistanceKm(List.of(
                new double[]{14.0, 50.0},
                new double[]{14.0, 51.0},
                new double[]{15.0, 51.0}
        ));
        assertThat(d).isCloseTo(181.2, within(2.0));
    }

    @Test
    void haversineKm_zeroForSamePoint() {
        double d = WaypointSelector.haversineKm(new double[]{14.0, 50.0}, new double[]{14.0, 50.0});
        assertThat(d).isZero();
    }

    @Test
    void haversineKm_PragueToKošice_around540km() {
        double[] praha = {14.42, 50.08};
        double[] kosice = {21.26, 48.72};
        double d = WaypointSelector.haversineKm(praha, kosice);
        assertThat(d).isCloseTo(518, within(10.0)); // ~518 km linia prosta
    }

    @Test
    void selectWaypoints_addsStartAndEnd() {
        List<UnvisitedArea> cluster = List.of(area(1, 14.5, 50.0));
        double[] start = {14.0, 50.0};
        double[] end = {15.0, 50.0};
        var result = selector.selectWaypoints(cluster, start, end, false);
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(start);
        assertThat(result.get(result.size() - 1)).isEqualTo(end);
    }

    @Test
    void selectWaypoints_loop_addsStartAtEnd() {
        List<UnvisitedArea> cluster = List.of(area(1, 14.5, 50.0));
        double[] start = {14.0, 50.0};
        var result = selector.selectWaypoints(cluster, start, null, true);
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(start);
        assertThat(result.get(result.size() - 1)).isEqualTo(start);
    }

    @Test
    void pointInRing_inside() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        assertThat(WaypointSelector.pointInRing(new double[]{14.5, 50.5}, ring)).isTrue();
    }

    @Test
    void pointInRing_outside() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        assertThat(WaypointSelector.pointInRing(new double[]{13.0, 50.5}, ring)).isFalse();
    }

    @Test
    void snapAreasToCorridor_movesPointTowardsCorridor() {
        // Area z ringem, centroid (14.5, 50.0). Sąsiedzi prev (14.0, 50.0) i next (15.0, 50.0).
        // Korytarz to oś 50.0 latitude. Snap powinien zostawić punkt w ringu BLISKO osi.
        UnvisitedArea a = ringArea(1, 14.5, 50.0);
        var ordered = List.of(a);
        var snapped = selector.snapAreasToCorridor(ordered, new double[]{14.0, 50.0}, new double[]{15.0, 50.0});
        assertThat(snapped).hasSize(1);
        // Punkt zostaje w ringu (lat między 49.975 a 50.025).
        assertThat(snapped.get(0).lat()).isBetween(49.975, 50.025);
        assertThat(snapped.get(0).lng()).isBetween(14.475, 14.525);
    }

    @Test
    void orderAreas_emptyList_returnsEmpty() {
        var result = selector.orderAreas(List.of(), new double[]{14.0, 50.0}, new double[]{15.0, 50.0});
        assertThat(result).isEmpty();
    }
}
