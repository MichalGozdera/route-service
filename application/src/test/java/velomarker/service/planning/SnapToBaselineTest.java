package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Test pakietu snap-to-baseline (Faza 4) — bezpośrednia weryfikacja core'u nowego algorytmu:
 * scoreAreaAgainstBaseline / findNearestGeomIdx.
 *
 * <p>Algorytm: zamiast TSP przez centroidy gmin, baseline BRouter (start→via→meta) JEST trasą,
 * a gminy są DOLEPIANE mini-detorami na granicę polygonu — wjazd ~50 m za granicę wystarczy
 * by intersect zaliczyło gminę. Tu sprawdzamy:
 * <ul>
 *   <li>gmina PRZECIĘTA przez baseline → intersected=true, detour=0 (darmo)</li>
 *   <li>gmina BLISKO baseline (offset 5 km) → intersected=false, detour~10 km</li>
 *   <li>gmina DALEKO (offset 30 km) → intersected=false, detour~60 km</li>
 *   <li>insertionIdx zachowuje monotonię wzdłuż baseline</li>
 *   <li>entryLng/entryLat są WEWNĄTRZ ringa (nie na granicy ani poza)</li>
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
    void insertionIdx_increasesWithLngForAreasAlongLine() {
        // 4 gminy przesunięte na N od bazowej, ułożone od zachodu na wschód → insertionIdx rosnie monotonicznie.
        var baseline = straightBaseline();
        var a1 = squareArea(1, 14.5, 50.05, 0.02);
        var a2 = squareArea(2, 15.5, 50.05, 0.02);
        var a3 = squareArea(3, 16.5, 50.05, 0.02);
        var a4 = squareArea(4, 17.5, 50.05, 0.02);
        var c1 = CoverageAreaSelection.scoreAreaAgainstBaseline(a1, baseline, false);
        var c2 = CoverageAreaSelection.scoreAreaAgainstBaseline(a2, baseline, false);
        var c3 = CoverageAreaSelection.scoreAreaAgainstBaseline(a3, baseline, false);
        var c4 = CoverageAreaSelection.scoreAreaAgainstBaseline(a4, baseline, false);
        assertThat(c1.getInsertionIdx()).isLessThan(c2.getInsertionIdx());
        assertThat(c2.getInsertionIdx()).isLessThan(c3.getInsertionIdx());
        assertThat(c3.getInsertionIdx()).isLessThan(c4.getInsertionIdx());
    }

    @Test
    void entryPoint_isInsideRing_notAtCentroid() {
        // Gmina 5 km na N od bazowej. Entry point powinien być NA POŁUDNIE od centroidu (bliżej bazowej)
        // i wewnątrz ringa.
        double lng = 16.0;
        double lat = 50.045;
        double half = 0.02;
        var area = squareArea(1, lng, lat, half);
        var c = CoverageAreaSelection.scoreAreaAgainstBaseline(area, straightBaseline(), false);
        // Wewnątrz ringa:
        assertThat(c.getEntryLng()).isBetween(lng - half, lng + half);
        assertThat(c.getEntryLat()).isBetween(lat - half, lat + half);
        // Bliżej południowej granicy (bazowa) niż centroid:
        assertThat(c.getEntryLat()).isLessThan(lat);
    }

    @Test
    void findNearestGeomIdx_returnsClosestIndex() {
        var baseline = straightBaseline(); // od 14.0 do 17.96 co 0.04
        int idx = PlanningGeom.findNearestGeomIdx(baseline, new double[]{16.0, 50.0});
        // 16.0 = 14.0 + 0.04 × 50 → indeks 50
        assertThat(idx).isEqualTo(50);
    }

    @Test
    void findNearestGeomIdx_targetBeyondPolyline_returnsLast() {
        var baseline = straightBaseline();
        int idx = PlanningGeom.findNearestGeomIdx(baseline, new double[]{30.0, 50.0});
        assertThat(idx).isEqualTo(baseline.size() - 1);
    }

    @Test
    void scoreArea_nullRing_doesNotCrash() {
        // Brak ring (UnvisitedArea bez geometrii) — kandidat z intersected=false, entry=centroid.
        var area = UnvisitedArea.levelMulti(1, "noRing", 50.05, 16.0, List.of(new AreaPart(null, null)), 1, 1, "gmina");
        var c = CoverageAreaSelection.scoreAreaAgainstBaseline(area, straightBaseline(), false);
        assertThat(c.isIntersected()).isFalse();
        assertThat(c.getEntryLng()).isEqualTo(16.0);
        assertThat(c.getEntryLat()).isEqualTo(50.05);
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
