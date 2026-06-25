package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budowa waypointów z wybranych gmin + helpery geometryczne:
 * <ul>
 *   <li>{@code buildWaypointsFromPicked}: utrzymuje kolejność anchors w finalnej liście</li>
 *   <li>{@code segmentIntersectsRing}: przecięcie segmentu z ringiem gminy</li>
 * </ul>
 */
class TrimAndDedupTest {

    private static List<double[]> straightBaseline() {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 100; i++) pts.add(new double[]{14.0 + i * 0.04, 50.0});
        return pts;
    }

    private static UnvisitedArea squareArea(int id, double lng, double lat, double halfSizeDeg) {
        double[][] ring = {
                {lng - halfSizeDeg, lat - halfSizeDeg}, {lng + halfSizeDeg, lat - halfSizeDeg},
                {lng + halfSizeDeg, lat + halfSizeDeg}, {lng - halfSizeDeg, lat + halfSizeDeg}
        };
        return new UnvisitedArea(id, "A" + id, lat, lng, ring, 1, 1, "gmina", null);
    }

    private static AreaCandidate scored(int id, double lng, double lat, double half, List<double[]> baseline) {
        var a = squareArea(id, lng, lat, half);
        return CoverageAreaSelection.scoreAreaAgainstBaseline(a, baseline, false);
    }

    private static RoutePreferences prefs(Waypoint start, Waypoint end, List<Waypoint> via) {
        return new RoutePreferences(List.of(), List.of(), List.of(), start, end, via,
                false, 10, 100, 1000, "fastbike");
    }

    @Test
    void buildWaypointsFromPicked_respectsAnchorOrder_withVia() {
        var baseline = straightBaseline();
        // 3 gminy: G1 (lng 14.5, idx ~12), G2 (lng 16.0, idx ~50), G3 (lng 17.5, idx ~87).
        var g1 = scored(1, 14.5, 50.05, 0.02, baseline);
        var g2 = scored(2, 16.0, 50.05, 0.02, baseline);
        var g3 = scored(3, 17.5, 50.05, 0.02, baseline);

        var startWp = new Waypoint(14.0, 50.0, "start");
        var viaWp = new Waypoint(16.0, 50.0, "via");
        var endWp = new Waypoint(17.96, 50.0, "end");

        var result = CoverageAreaSelection.buildWaypointsFromPicked(
                prefs(startWp, endWp, List.of(viaWp)),
                List.of(g1, g2, g3),
                baseline);

        // Oczekiwana kolejność: start, G1, via, G3, end. G2 może wpaść przed via (idx 50 = via idx),
        // albo dokładnie do via. Spr. że START, VIA, END są w kolejności + gminy SĄ w środku.
        assertThat(result).hasSizeGreaterThanOrEqualTo(5);
        assertThat(result.get(0)).isEqualTo(startWp);
        assertThat(result.get(result.size() - 1)).isEqualTo(endWp);
        int viaIdx = result.indexOf(viaWp);
        assertThat(viaIdx).isPositive();
        // G1 (lng 14.5) musi być PRZED via (lng 16.0):
        int g1Idx = -1;
        for (int i = 0; i < result.size(); i++) {
            Waypoint w = result.get(i);
            if (w.name() != null && w.name().equals("A1")) { g1Idx = i; break; }
        }
        assertThat(g1Idx).isPositive();
        assertThat(g1Idx).isLessThan(viaIdx);
        // G3 (lng 17.5) musi być PO via (lng 16.0):
        int g3Idx = -1;
        for (int i = 0; i < result.size(); i++) {
            Waypoint w = result.get(i);
            if (w.name() != null && w.name().equals("A3")) { g3Idx = i; break; }
        }
        assertThat(g3Idx).isPositive();
        assertThat(g3Idx).isGreaterThan(viaIdx);
    }

    @Test
    void greedyPick_leavesReserve_whenBudgetTight() {
        // 5 gmin: 3 mieszczą się w surplusie, 2 nie (drogie) — reserve powinien mieć 2.
        // To jest dokumentacja zachowania greedy_pick (które wbudowane w buildCoverageWaypointsWithInfo),
        // tu testujemy że IDEA reserve działa: candidates sorted ASC, picked = prefix do budgetu, reserve = sufiks.
        var baseline = straightBaseline();
        List<AreaCandidate> sorted = new ArrayList<>(List.of(
                scored(1, 14.5, 50.05, 0.02, baseline),
                scored(2, 15.5, 50.05, 0.02, baseline),
                scored(3, 16.5, 50.05, 0.02, baseline),
                scored(4, 16.0, 50.30, 0.02, baseline),
                scored(5, 16.0, 50.60, 0.02, baseline)
        ));
        sorted.sort((a, b) -> Double.compare(a.getDetourStraightKm(), b.getDetourStraightKm()));
        // Simulate budget surplus = 60 km (tylko najtańsze 3 wejdą).
        double surplus = 60;
        double roadAreas = 1.5;
        List<AreaCandidate> picked = new ArrayList<>();
        List<AreaCandidate> reserve = new ArrayList<>();
        double used = 0;
        for (var c : sorted) {
            double real = c.isIntersected() ? 0 : c.getDetourStraightKm() * roadAreas;
            if (used + real > surplus && !c.isIntersected()) {
                reserve.add(c);
                continue;
            }
            picked.add(c);
            used += real;
        }
        assertThat(picked.size() + reserve.size()).isEqualTo(5);
        assertThat(reserve).isNotEmpty();
        // Najtańsze w picked, najdroższe w reserve:
        double maxPickedDetour = picked.stream().mapToDouble(AreaCandidate::getDetourStraightKm).max().orElseThrow();
        double minReserveDetour = reserve.stream().mapToDouble(AreaCandidate::getDetourStraightKm).min().orElseThrow();
        assertThat(maxPickedDetour).isLessThanOrEqualTo(minReserveDetour);
    }

    @Test
    void segmentIntersectsRing_endpointInside_returnsTrue() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        assertThat(PlanningGeom.segmentIntersectsRing(
                new double[]{14.5, 50.5}, new double[]{13.0, 49.0}, ring)).isTrue();
    }

    @Test
    void segmentIntersectsRing_crossingSegment_returnsTrue() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        // Segment crosses ring left-to-right
        assertThat(PlanningGeom.segmentIntersectsRing(
                new double[]{13.5, 50.5}, new double[]{15.5, 50.5}, ring)).isTrue();
    }

    @Test
    void segmentIntersectsRing_outsideBbox_returnsFalse() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        // Segment far away
        assertThat(PlanningGeom.segmentIntersectsRing(
                new double[]{20.0, 60.0}, new double[]{21.0, 60.0}, ring)).isFalse();
    }
}
