package velomarker.service.planning;

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

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Test pakietu snap-to-baseline (Faza 4) — bezpośrednia weryfikacja core'u nowego algorytmu:
 * scoreAreaAgainstBaseline.
 *
 * <p>Algorytm: zamiast TSP przez centroidy gmin, baseline BRouter (start→via→meta) JEST trasą,
 * a gminy są DOLEPIANE mini-detorami na granicę polygonu — wjazd ~50 m za granicę wystarczy
 * by intersect zaliczyło gminę. Tu sprawdzamy:
 * <ul>
 *   <li>gmina PRZECIĘTA przez baseline → intersected=true, detour=0 (darmo)</li>
 *   <li>gmina BLISKO baseline (offset 5 km) → intersected=false, detour~10 km</li>
 *   <li>gmina DALEKO (offset 30 km) → intersected=false, detour~60 km</li>
 * </ul>
 */
class SnapToBaselineTest {

    /** Baseline polyline: linia prosta (14.0, 50.0) → (18.0, 50.0), 100 punktów co ~4 km. */
    private static List<double[]> straightBaseline() {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pts.add(new double[]{14.0 + i * 0.04, 50.0});
        }
        return pts;
    }

    /** Gmina-kwadrat 5×5 km wokół (lng, lat). */
    private static UnvisitedArea squareArea(int id, double lng, double lat, double halfSizeDeg) {
        double[][] ring = {
                {lng - halfSizeDeg, lat - halfSizeDeg}, {lng + halfSizeDeg, lat - halfSizeDeg},
                {lng + halfSizeDeg, lat + halfSizeDeg}, {lng - halfSizeDeg, lat + halfSizeDeg}
        };
        return new UnvisitedArea(id, "A" + id, lat, lng, ring, 1, 1, "gmina", null);
    }

    @Test
    void areaCrossedByBaseline_isIntersected_zeroDetour() {
        // Baseline na lat 50.0, gmina centroid (16.0, 50.0) z ringiem ±0.02° (~2 km) → baseline przechodzi przez środek.
        var area = squareArea(1, 16.0, 50.0, 0.02);
        var c = CoverageAreaSelection.scoreAreaAgainstBaseline(area, straightBaseline(), true);
        assertThat(c.isIntersected()).isTrue();
        assertThat(c.getDetourStraightKm()).isZero();
    }

    @Test
    void areaAlongsideBaseline_5kmOff_isNotIntersected_smallDetour() {
        // Gmina przesunięta o 0.07° latitude (~7-8 km na północ) z ringiem ±0.02° → NIE przecina linii lat=50.
        var area = squareArea(2, 16.0, 50.07, 0.02);
        var c = CoverageAreaSelection.scoreAreaAgainstBaseline(area, straightBaseline(), false);
        assertThat(c.isIntersected()).isFalse();
        // dist do bazowej = ~7-8 km haversine, detour = 2× + 0.2 ~ 15-17 km
        assertThat(c.getDetourStraightKm()).isCloseTo(15.8, within(2.0));
    }

    @Test
    void areaFarFromBaseline_largeDetour() {
        // Gmina 30 km na południe od bazowej.
        var area = squareArea(3, 16.0, 49.73, 0.02);
        var c = CoverageAreaSelection.scoreAreaAgainstBaseline(area, straightBaseline(), false);
        assertThat(c.isIntersected()).isFalse();
        // Detour > 50 km — drogi, trim wyrzuci.
        assertThat(c.getDetourStraightKm()).isGreaterThan(50.0);
    }

    @Test
    void greedy_freeAreasFirstThenCheapest_simulatesAlgorithm() {
        // Symulacja: 3 wolne (intersected), 5 płatnych po różnej cenie. Po sort detourStraight ASC,
        // pierwsze 3 mają detour=0, kolejne rosną.
        var baseline = straightBaseline();
        List<UnvisitedArea> areas = List.of(
                squareArea(1, 14.5, 50.0, 0.03), // intersected
                squareArea(2, 17.0, 50.0, 0.03), // intersected
                squareArea(3, 15.5, 50.0, 0.03), // intersected
                squareArea(4, 16.0, 50.07, 0.02), // 7-8 km off
                squareArea(5, 16.0, 50.15, 0.02), // 15 km off
                squareArea(6, 16.0, 50.27, 0.02), // 27 km off
                squareArea(7, 16.0, 50.45, 0.02), // 45 km off
                squareArea(8, 16.0, 50.70, 0.02)  // 70 km off
        );
        List<AreaCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < areas.size(); i++) {
            // pierwsze 3 gminy leżą NA baseline (intersected), reszta z boku
            candidates.add(CoverageAreaSelection.scoreAreaAgainstBaseline(areas.get(i), baseline, i < 3));
        }
        candidates.sort((x, y) -> Double.compare(x.getDetourStraightKm(), y.getDetourStraightKm()));
        // Top 3 = intersected (detour=0):
        assertThat(candidates.get(0).isIntersected()).isTrue();
        assertThat(candidates.get(1).isIntersected()).isTrue();
        assertThat(candidates.get(2).isIntersected()).isTrue();
        // 4-ty = najtańszy płatny (~7 km off → detour ~15.8 km):
        assertThat(candidates.get(3).isIntersected()).isFalse();
        assertThat(candidates.get(3).getDetourStraightKm()).isCloseTo(15.8, within(3.0));
        // Ostatni najdroższy (~70 km off → detour >120 km):
        assertThat(candidates.get(7).getDetourStraightKm()).isGreaterThan(120.0);
    }
}
