package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faza 5 — meta-must + dedup gmin pokrytych naturalnie:
 * <ul>
 *   <li>{@code trimExpensiveGminy}: wyrzuca najdroższe gminy gdy meta nie osiągnięta</li>
 *   <li>{@code removeNaturallyCoveredEntries}: usuwa entry-pointy gmin pokrytych przez nową trasę</li>
 *   <li>{@code buildWaypointsFromPicked}: utrzymuje kolejność anchors w finalnej liście</li>
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
        return new UnvisitedArea(id, "A" + id, null, lat, lng, ring, 1, 1, "gmina", null);
    }

    private static PlanningOrchestrationService.AreaCandidate scored(int id, double lng, double lat, double half, List<double[]> baseline) {
        var a = squareArea(id, lng, lat, half);
        return PlanningOrchestrationService.scoreAreaAgainstBaseline(a, baseline);
    }

    private static RoutePreferences prefs(Waypoint start, Waypoint end, List<Waypoint> via) {
        return new RoutePreferences(List.of(), List.of(), List.of(), start, end, via,
                false, 10, 100, 1000, "fastbike", null, null);
    }

    private static PlanningOrchestrationService.CoverageBuildInfo coverageInfo(
            List<PlanningOrchestrationService.AreaCandidate> picked, List<double[]> baselineGeom) {
        return new PlanningOrchestrationService.CoverageBuildInfo(
                List.of(), 0, picked.size(), 0, 0, 0,
                BudgetReconciler.Verdict.OK, 100.0, 1.4, 1.1, picked, List.of(), baselineGeom);
    }

    @Test
    void trimExpensiveGminy_drops30Percent_byDetour() {
        var baseline = straightBaseline();
        var picked = new ArrayList<PlanningOrchestrationService.AreaCandidate>();
        // 10 gmin: 0..4 = cheap (5 km off), 5..9 = expensive (40 km off).
        for (int i = 0; i < 5; i++) picked.add(scored(i, 14.5 + i * 0.5, 50.05, 0.02, baseline));
        for (int i = 5; i < 10; i++) picked.add(scored(i, 14.5 + (i - 5) * 0.5, 50.36, 0.02, baseline));

        var startWp = new Waypoint(14.0, 50.0, "start");
        var endWp = new Waypoint(17.96, 50.0, "end");
        var info = coverageInfo(picked, baseline);

        // Wyrzuć 30% (3 najdroższe).
        var trimmedWps = PlanningOrchestrationService.trimExpensiveGminy(prefs(startWp, endWp, List.of()), info, 0.3);
        // Final list = start + 7 gmin + end = 9 wp.
        assertThat(trimmedWps).hasSize(9);
        assertThat(trimmedWps.get(0)).isEqualTo(startWp);
        assertThat(trimmedWps.get(trimmedWps.size() - 1)).isEqualTo(endWp);
    }

    @Test
    void trimExpensiveGminy_drop100percent_returnsBaselineAnchorsOnly() {
        var baseline = straightBaseline();
        var picked = List.of(
                scored(1, 14.5, 50.05, 0.02, baseline),
                scored(2, 15.5, 50.05, 0.02, baseline));
        var info = coverageInfo(picked, baseline);
        var startWp = new Waypoint(14.0, 50.0, "start");
        var endWp = new Waypoint(17.96, 50.0, "end");

        var trimmedWps = PlanningOrchestrationService.trimExpensiveGminy(prefs(startWp, endWp, List.of()), info, 1.0);
        // Wszystkie gminy wyrzucone → tylko start + end.
        assertThat(trimmedWps).hasSize(2);
        assertThat(trimmedWps.get(0)).isEqualTo(startWp);
        assertThat(trimmedWps.get(1)).isEqualTo(endWp);
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

        var result = PlanningOrchestrationService.buildWaypointsFromPicked(
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
    void removeNaturallyCoveredEntries_dropsCoveredGmina() {
        // Baseline original = linia lat=50.0. Po pierwszym BRouter, newGeometry przechodzi przez TĘ SAMĄ linię
        // ALE ALSO doszla na lat=50.05 (przez gminę G1). G1 oryginalnie miała entry-point bo NIE była intersected
        // by baseline. Teraz NEW geometry przecina G1 ring naturalnie → dropowalna.
        var baseline = straightBaseline();
        // G1 centroid (15.0, 50.05), ring ±0.04° → przecina lat 50.05 ale NIE 50.0.
        var g1 = scored(1, 15.0, 50.05, 0.04, baseline);
        assertThat(g1.isIntersected()).as("G1 nie powinna być intersected by original baseline").isFalse();

        // newGeometry: jak baseline ALE w środku skacze na lat 50.05 (przez gminę G1).
        var newGeom = new ArrayList<double[]>();
        for (int i = 0; i < 100; i++) {
            if (i >= 20 && i <= 30) newGeom.add(new double[]{14.0 + i * 0.04, 50.05});
            else newGeom.add(new double[]{14.0 + i * 0.04, 50.0});
        }

        var startWp = new Waypoint(14.0, 50.0, "start");
        var endWp = new Waypoint(17.96, 50.0, "end");
        var info = coverageInfo(List.of(g1), baseline);

        // currentWps = [start, A1 entry-point, end].
        var entry = new Waypoint(g1.getEntryLng(), g1.getEntryLat(), g1.getArea().name());
        var currentWps = List.of(startWp, entry, endWp);

        var deduped = PlanningOrchestrationService.removeNaturallyCoveredEntries(
                prefs(startWp, endWp, List.of()), info, newGeom, currentWps);
        // G1 naturalnie pokryta → entry-point wyrzucony.
        assertThat(deduped).hasSize(2);
        assertThat(deduped.get(0)).isEqualTo(startWp);
        assertThat(deduped.get(1)).isEqualTo(endWp);
    }

    @Test
    void removeNaturallyCoveredEntries_nameCollisionWithUserAnchor_keepsAll() {
        // Edge case: user dał via z nazwą "A1" (zbieżną z gminą). NIE umiemy odróżnić po nazwie,
        // więc preferujemy BEZPIECZNY scenariusz — NIE wyrzucamy żadnego "A1". Test sprawdza że
        // gmina entry-point Z NAZWĄ KOLIDUJĄCĄ z user-anchor ZOSTAJE (bo nie umiemy odróżnić).
        var baseline = straightBaseline();
        var g1 = scored(1, 15.0, 50.05, 0.04, baseline);
        var newGeom = new ArrayList<double[]>();
        for (int i = 0; i < 100; i++) {
            if (i >= 20 && i <= 30) newGeom.add(new double[]{14.0 + i * 0.04, 50.05});
            else newGeom.add(new double[]{14.0 + i * 0.04, 50.0});
        }

        var startWp = new Waypoint(14.0, 50.0, "start");
        var endWp = new Waypoint(17.96, 50.0, "end");
        var collidingVia = new Waypoint(15.5, 50.0, "A1");
        var info = coverageInfo(List.of(g1), baseline);

        var currentWps = List.of(startWp, collidingVia, new Waypoint(g1.getEntryLng(), g1.getEntryLat(), "A1"), endWp);

        var deduped = PlanningOrchestrationService.removeNaturallyCoveredEntries(
                prefs(startWp, endWp, List.of(collidingVia)), info, newGeom, currentWps);
        // collidingVia + gmina entry-point z tą samą nazwą — bez zmian (safety preferred).
        assertThat(deduped).hasSize(4);
        assertThat(deduped).contains(collidingVia);
    }

    @Test
    void greedyPick_leavesReserve_whenBudgetTight() {
        // 5 gmin: 3 mieszczą się w surplusie, 2 nie (drogie) — reserve powinien mieć 2.
        // To jest dokumentacja zachowania greedy_pick (które wbudowane w buildCoverageWaypointsWithInfo),
        // tu testujemy że IDEA reserve działa: candidates sorted ASC, picked = prefix do budgetu, reserve = sufiks.
        var baseline = straightBaseline();
        List<PlanningOrchestrationService.AreaCandidate> sorted = new ArrayList<>(List.of(
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
        List<PlanningOrchestrationService.AreaCandidate> picked = new ArrayList<>();
        List<PlanningOrchestrationService.AreaCandidate> reserve = new ArrayList<>();
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
        double maxPickedDetour = picked.stream().mapToDouble(PlanningOrchestrationService.AreaCandidate::getDetourStraightKm).max().orElseThrow();
        double minReserveDetour = reserve.stream().mapToDouble(PlanningOrchestrationService.AreaCandidate::getDetourStraightKm).min().orElseThrow();
        assertThat(maxPickedDetour).isLessThanOrEqualTo(minReserveDetour);
    }

    @Test
    void dedupByMutualCoverage_entryInsideNeighborRing_dropsRedundant() {
        // 3 gminy w klastrze: B's entry leży w A.ring → A jest "covered" gdy BRouter idzie do B.
        // Zachowaj tę z mniejszym detour.
        var baseline = straightBaseline();
        // A: large gmina centroid (15.0, 50.05) ringi ±0.05° (~5km half-width)
        var a = new UnvisitedArea(1, "A", null, 50.05, 15.0,
                new double[][]{{14.95, 50.0}, {15.05, 50.0}, {15.05, 50.10}, {14.95, 50.10}},
                1, 1, "gmina", null);
        // B: small gmina inside A — entry-point ~(14.98, 50.04) leży w A.ring
        var b = new UnvisitedArea(2, "B", null, 50.04, 14.98,
                new double[][]{{14.975, 50.035}, {14.985, 50.035}, {14.985, 50.045}, {14.975, 50.045}},
                1, 1, "gmina", null);
        // C: gmina daleko od A i B — nie skipowalna
        var c = new UnvisitedArea(3, "C", null, 50.05, 17.0,
                new double[][]{{16.95, 50.0}, {17.05, 50.0}, {17.05, 50.10}, {16.95, 50.10}},
                1, 1, "gmina", null);

        var ca = PlanningOrchestrationService.scoreAreaAgainstBaseline(a, baseline);
        var cb = PlanningOrchestrationService.scoreAreaAgainstBaseline(b, baseline);
        var cc = PlanningOrchestrationService.scoreAreaAgainstBaseline(c, baseline);

        var picked = new java.util.ArrayList<>(java.util.List.of(ca, cb, cc));
        var deduped = PlanningOrchestrationService.dedupByMutualCoverage(picked);

        // Iter 9 Fix #1: NIE usuwamy areas z listy, tylko flagujemy mutually-covered.
        // A i B overlap → JEDNA z nich oznaczona flagą (większy detour). C bez flagi.
        assertThat(deduped).hasSize(3);
        long flaggedCount = deduped.stream()
                .filter(PlanningOrchestrationService.AreaCandidate::isMutuallyCoveredByNeighbor)
                .count();
        assertThat(flaggedCount).isGreaterThanOrEqualTo(1);
        // C bez flagi
        var cFlag = deduped.stream().filter(x -> x.getArea().name().equals("C")).findFirst().orElseThrow();
        assertThat(cFlag.isMutuallyCoveredByNeighbor()).isFalse();
    }

    @Test
    void dedupByMutualCoverage_noOverlap_keepsAll() {
        var baseline = straightBaseline();
        var picked = List.of(
                scored(1, 14.5, 50.05, 0.02, baseline),
                scored(2, 15.5, 50.05, 0.02, baseline),
                scored(3, 16.5, 50.05, 0.02, baseline)
        );
        var deduped = PlanningOrchestrationService.dedupByMutualCoverage(picked);
        assertThat(deduped).hasSize(3);
    }

    @Test
    void segmentIntersectsRing_endpointInside_returnsTrue() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        assertThat(PlanningOrchestrationService.segmentIntersectsRing(
                new double[]{14.5, 50.5}, new double[]{13.0, 49.0}, ring)).isTrue();
    }

    @Test
    void segmentIntersectsRing_crossingSegment_returnsTrue() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        // Segment crosses ring left-to-right
        assertThat(PlanningOrchestrationService.segmentIntersectsRing(
                new double[]{13.5, 50.5}, new double[]{15.5, 50.5}, ring)).isTrue();
    }

    @Test
    void segmentIntersectsRing_outsideBbox_returnsFalse() {
        double[][] ring = {{14.0, 50.0}, {15.0, 50.0}, {15.0, 51.0}, {14.0, 51.0}};
        // Segment far away
        assertThat(PlanningOrchestrationService.segmentIntersectsRing(
                new double[]{20.0, 60.0}, new double[]{21.0, 60.0}, ring)).isFalse();
    }

    @Test
    void removeNaturallyCoveredEntries_noCovered_keepsAll() {
        var baseline = straightBaseline();
        var g1 = scored(1, 15.0, 50.10, 0.02, baseline); // 10 km off

        // newGeometry = baseline (NIE przecina G1).
        var newGeom = new ArrayList<>(baseline);

        var startWp = new Waypoint(14.0, 50.0, "start");
        var endWp = new Waypoint(17.96, 50.0, "end");
        var info = coverageInfo(List.of(g1), baseline);
        var entry = new Waypoint(g1.getEntryLng(), g1.getEntryLat(), g1.getArea().name());
        var currentWps = List.of(startWp, entry, endWp);

        var deduped = PlanningOrchestrationService.removeNaturallyCoveredEntries(
                prefs(startWp, endWp, List.of()), info, newGeom, currentWps);
        assertThat(deduped).hasSize(3); // bez zmian
    }
}
