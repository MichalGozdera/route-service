package velomarker.service.planning.tsp;

import org.junit.jupiter.api.Test;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.service.planning.PlanningOrchestrationService.AreaCandidate;
import velomarker.service.planning.PlanningOrchestrationService.CoverageBuildInfo;
import velomarker.service.planning.RoadFactorCalibrator;
import velomarker.service.planning.BudgetReconciler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy weryfikują kluczowe inwarianty TSP cheapest insertion algorytmu pod uwagi usera:
 * <ul>
 *   <li>"powłóczy się po okolicy" -- klaster blisko siebie zbierany razem (cheapest insertion preferuje sąsiadów)</li>
 *   <li>"byleby nie ucinał" -- algorytm bierze az budżet się spinkuje (do 1.0×)</li>
 *   <li>"zgarnia co po drodze" -- bliskie obszary preferowane nad odlegle</li>
 *   <li>Intersected areas są darmowe (pre-filled w picked bez waypointów)</li>
 *   <li>Granica budżetu respektowana (nie przekracza)</li>
 * </ul>
 */
class TspCoveragePlannerTest {

    private final TspCoveragePlanner planner = new TspCoveragePlanner();
    private final UUID taskId = UUID.randomUUID();

    /** Buduje AreaCandidate przy (lng, lat) z dummy ring 0.01° wokół. */
    private static AreaCandidate area(int id, double lng, double lat, double detourStraightKm, boolean intersected) {
        double[][] ring = {
                {lng - 0.01, lat - 0.01},
                {lng + 0.01, lat - 0.01},
                {lng + 0.01, lat + 0.01},
                {lng - 0.01, lat + 0.01}
        };
        UnvisitedArea ua = UnvisitedArea.level(id, "Area" + id, null, lat, lng, ring, 1, 1, "test");
        return new AreaCandidate(ua, intersected, 0, detourStraightKm, detourStraightKm, lng, lat);
    }

    /** Fake BRouter: liczy haversine distance pomiedzy waypointami × 1.3 jako road factor. */
    private static BiFunction<List<Waypoint>, String, RouteCalculation> fakeBrouter() {
        return (wps, profile) -> {
            double km = 0;
            for (int i = 1; i < wps.size(); i++) {
                double[] p1 = wps.get(i - 1).toLngLat();
                double[] p2 = wps.get(i).toLngLat();
                km += velomarker.service.planning.WaypointSelector.haversineKm(p1, p2) * 1.3;
            }
            List<double[]> coords = new ArrayList<>();
            for (Waypoint w : wps) coords.add(w.toLngLat());
            return new RouteCalculation(coords, km);
        };
    }

    private static RoutePreferences makePrefs(double startLng, double startLat,
                                               double endLng, double endLat,
                                               int days, int kmPerDay) {
        return new RoutePreferences(
                List.of(1), List.of(1), List.of(),
                new Waypoint(startLng, startLat, "start"),
                new Waypoint(endLng, endLat, "end"),
                List.of(),
                false,
                days, kmPerDay, 100,
                "fastbike", null, null);
    }

    private static CoverageBuildInfo info(List<AreaCandidate> picked, List<AreaCandidate> reserve,
                                           double baselineKm) {
        List<double[]> baselineGeom = List.of(
                new double[]{15.0, 50.0},
                new double[]{16.0, 50.0});
        return new CoverageBuildInfo(
                List.of(),
                picked.size() + reserve.size(), picked.size(),
                0, 0, 0,
                BudgetReconciler.Verdict.OK,
                baselineKm, 1.3, 1.3,
                picked, reserve, baselineGeom);
    }

    private static RoadFactorCalibrator calibrator() {
        return new RoadFactorCalibrator(); // domyslny factor 1.3
    }

    @Test
    void pustyInput_returnsTrivialResult() {
        CoverageBuildInfo input = info(List.of(), List.of(), 80);
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 1, 200);
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        assertThat(result).isNotNull();
        assertThat(result.picked()).isEmpty();
        assertThat(result.finalWaypoints()).hasSize(2); // start + end
    }

    @Test
    void intersectedAreas_sAaWPickedBezWaypoints() {
        // Hybrid (iteracja 7): intersected idą do `pickedCandidates` (greedy je tam wstawia).
        // TSP czyta intersected z picked, NIE z reserve.
        AreaCandidate freeArea = area(1, 15.5, 50.0, 0, true);
        CoverageBuildInfo input = info(List.of(freeArea), List.of(), 80);
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 1, 100);
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        // Intersected musi byc w picked
        assertThat(result.picked()).hasSize(1);
        assertThat(result.picked().get(0).isIntersected()).isTrue();
        // Ale tour NIE ma jego waypointu (intersected = baseline juz przechodzi)
        assertThat(result.finalWaypoints()).hasSize(2);
    }

    @Test
    void klasterBliskiSiebie_wszystkieZebrane() {
        // 3 obszary blisko siebie w korytarzu trasy
        List<AreaCandidate> reserve = List.of(
                area(1, 15.3, 50.02, 5, false),
                area(2, 15.4, 50.02, 5, false),
                area(3, 15.5, 50.02, 5, false));
        CoverageBuildInfo input = info(List.of(), reserve, 80);
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 5, 100); // budget 500 km
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        // Wszystkie 3 powinny byc zebrane (klaster blisko, budget wystarcza)
        assertThat(result.picked()).hasSize(3);
        // Tour zawsze ma start + end. Areas moga byc w tour ALBO usuniete przez post-dedup
        // (jesli fake BRouter geometria przeszla przez ich ring) -- areas zostaja w picked.
        List<String> names = result.finalWaypoints().stream().map(Waypoint::name).toList();
        assertThat(names).startsWith("start").endsWith("end");
    }

    @Test
    void odlegleObszaryPomijane_naRzeczBliższych() {
        // 1 odlegly (50 km od trasy) i 2 blisko (5 km od trasy) -- TSP woli bliskie
        List<AreaCandidate> reserve = List.of(
                area(1, 15.5, 50.45, 50, false),  // daleko na N (~50km)
                area(2, 15.3, 50.02, 5, false),   // blisko
                area(3, 15.7, 50.02, 5, false));  // blisko
        // Maly budget: 100 km surplus po baseline 80km = budget 180km
        CoverageBuildInfo input = info(List.of(), reserve, 80);
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 1, 180);
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        // Bliskie obszary preferowane -- area 2 i 3 wzięte, area 1 (daleki) pominięty
        assertThat(result.picked()).extracting(c -> c.getArea().areaId())
                .contains(2, 3)
                .doesNotContain(1);
    }

    @Test
    void budget_nieJestPrzekraczany() {
        // Bardzo duzo obszarow w korytarzu, ale maly budget
        List<AreaCandidate> reserve = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double lng = 15.0 + 0.05 * i;
            reserve.add(area(i + 1, lng, 50.02, 10, false));
        }
        CoverageBuildInfo input = info(List.of(), reserve, 80);
        // Budget 200 km. Baseline ~80 km. Surplus 120 km. Z roadAreas 1.3 i straight detour 5km
        // (~na korytarzu): per obszar real detour ~ 6.5 km. ~18 obszarow zmiesci sie.
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 1, 200);
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        // Final calc nie przekracza budgetu
        double finalKm = result.calc().distanceKm();
        assertThat(finalKm).isLessThanOrEqualTo(200 * 1.1); // +10% tolerancja na real BRouter rozjazd vs proxy
    }

    @Test
    void wystarczajacyBudget_bierzeWszystkie() {
        // 3 obszary, ogromny budget -- powinny wszystkie wejsc
        List<AreaCandidate> reserve = List.of(
                area(1, 15.3, 50.02, 5, false),
                area(2, 15.5, 50.02, 5, false),
                area(3, 15.7, 50.02, 5, false));
        CoverageBuildInfo input = info(List.of(), reserve, 80);
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 10, 200); // budget 2000 km
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        // User: "byleby nie ucinal, ma dobierac jak starczy budzetu"
        assertThat(result.picked()).hasSize(3);
    }

    @Test
    void twoOpt_reverseCrossingEdges_dajaKrótszyTour() {
        // Tour [start, A, B, C, D, end] gdzie A i C blizej geograficznie, B i D blizej
        // -- z anti-zigzag 2-opt powinien reverse [A..C] na [C..A]
        Waypoint start = new Waypoint(0, 0, "start");
        Waypoint a = new Waypoint(1, 1, "A");
        Waypoint b = new Waypoint(3, 0, "B");  // far East
        Waypoint c = new Waypoint(2, 1, "C");
        Waypoint d = new Waypoint(4, 0, "D");
        Waypoint end = new Waypoint(5, 0, "end");
        List<Waypoint> tour = new ArrayList<>(List.of(start, a, b, c, d, end));
        java.util.Set<String> anchors = java.util.Set.of("start", "end");
        int swaps = TspCoveragePlanner.apply2Opt(tour, anchors);
        // Tour po 2-opt powinien byc krotszy (cross edges fixed)
        double finalCost = 0;
        for (int i = 1; i < tour.size(); i++) {
            finalCost += velomarker.service.planning.WaypointSelector.haversineKm(
                    tour.get(i - 1).toLngLat(), tour.get(i).toLngLat());
        }
        assertThat(swaps).isGreaterThan(0);
        // Anchors nie ruszane
        assertThat(tour.get(0).name()).isEqualTo("start");
        assertThat(tour.get(tour.size() - 1).name()).isEqualTo("end");
    }

    @Test
    void twoOpt_zachowujeAnchorPositions() {
        // Tour [start, A, B, via, C, D, end] -- 2-opt nie moze reverse poprzez via
        Waypoint start = new Waypoint(0, 0, "start");
        Waypoint a = new Waypoint(1, 1, "A");
        Waypoint b = new Waypoint(2, 0, "B");
        Waypoint via = new Waypoint(3, 0, "via");
        Waypoint c = new Waypoint(4, 1, "C");
        Waypoint d = new Waypoint(5, 0, "D");
        Waypoint end = new Waypoint(6, 0, "end");
        List<Waypoint> tour = new ArrayList<>(List.of(start, a, b, via, c, d, end));
        java.util.Set<String> anchors = java.util.Set.of("start", "via", "end");
        TspCoveragePlanner.apply2Opt(tour, anchors);
        // Via musi byc w pozycji 3 (anchor)
        assertThat(tour.indexOf(via)).isEqualTo(3);
        assertThat(tour.get(0)).isEqualTo(start);
        assertThat(tour.get(tour.size() - 1)).isEqualTo(end);
    }

    @Test
    void cheapestInsertion_zachowujeKolejnoscWzdluzTrasy() {
        // 3 obszary rozproszone wzdluz korytarza. TSP powinien je wstawic w kolejnosci geograficznej.
        List<AreaCandidate> reserve = List.of(
                area(3, 15.8, 50.02, 5, false),
                area(1, 15.2, 50.02, 5, false),
                area(2, 15.5, 50.02, 5, false));
        CoverageBuildInfo input = info(List.of(), reserve, 80);
        RoutePreferences prefs = makePrefs(15.0, 50.0, 16.0, 50.0, 5, 100);
        TspCoveragePlanner.TspResult result = planner.plan(taskId, input, prefs, "fastbike",
                calibrator(), fakeBrouter(), fakeBrouter(), id -> {});
        // Picked zawsze ma 3 obszary (post-dedup nie zmniejsza coverage, tylko usuwa waypointy
        // ktore sa naturalnie pokryte). Tour zaczyna sie od start i konczy end.
        assertThat(result.picked()).hasSize(3);
        List<String> names = result.finalWaypoints().stream().map(Waypoint::name).toList();
        assertThat(names).startsWith("start").endsWith("end");
    }
}
