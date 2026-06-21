package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.ElevationProfile;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.planning.VisitServiceClient;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.AreaCoverageIndexFactory;
import java.util.Set;
import velomarker.exception.PlanningSessionNotReadyException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Przygotowanie surowej puli gmin dla COVERAGE intent: pobranie nieodwiedzonych z visit-service,
 * pre-screen baseline (rzuca gdy baseline > budget), JTS scoring gmin względem baseline.
 * 
 * <p>Gdy {@code CoveragePlanner} jest aktywny, wyniki (scored candidates + baseline) trafiają
 * do plannera, który sam decyduje o selekcji/dedup/śladzie.
 * 
 * <p>Gdy planner jest niedostępny (fallback), scored candidates są używane przez
 * {@code RouteTracer} z prostym greedy + trim.
 * 
 * <p>Wydzielone z PlanningOrchestrationService.
 */
final class CoverageWaypointBuilder {

    private static final Logger log = LoggerFactory.getLogger(CoverageWaypointBuilder.class);
    private static final int MAX_AREAS_TO_FETCH = 40000;

    private final VisitServiceClient visitClient;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> setPhase;
    private final AreaCoverageIndexFactory coverageIndexFactory;
    private final BaselineComputer baselineComputer;

    CoverageWaypointBuilder(VisitServiceClient visitClient,
                            WaypointSelector waypointSelector,
                            Consumer<UUID> checkCancel, BiConsumer<UUID, String> setPhase,
                            AreaCoverageIndexFactory coverageIndexFactory,
                            velomarker.port.in.CalculateRouteUseCase routeUseCase) {
        this.visitClient = visitClient;
        this.checkCancel = checkCancel;
        this.setPhase = setPhase;
        this.coverageIndexFactory = coverageIndexFactory;
        this.baselineComputer = new BaselineComputer(routeUseCase, waypointSelector);
    }

    /**
     * Pobiera pulę gmin, liczy baseline, robi JTS scoring względem baseline.
     * Zwraca CoverageBuildInfo z:
     * <ul>
     *   <li>{@code waypoints} = same anchory (planner/fallback zrobi finalne waypointy)</li>
     *   <li>{@code pickedCandidates} = WSZYSTKIE gminy po score (nie pre-selected przez greedy)</li>
     *   <li>metryki: poolSize, baselineKm, roadAreas, baselineGeometry</li>
     * </ul>
     */
    PlanningOrchestrationService.CoverageBuildInfo build(UUID taskId, RoutePreferences prefs, String bearerToken, String profile,
                                                  RoadFactorCalibrator calibrator) {
        List<UnvisitedArea> pool = fetchAreaPool(taskId, prefs, bearerToken);
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : 0;

        // ETAP 1: BASELINE — BRouter start→via→meta (rzuca gdy sam > budżet).
        BaselineComputer.BaselineProbe baseline = runBaseline(taskId, prefs, profile, calibrator, budgetKm);
        List<double[]> baselineGeom = baseline.geometry();

        // ETAP 2: SCORING — JTS intersect + detour dla każdej gminy.
        setPhase.accept(taskId, "scoring-areas");
        checkCancel.accept(taskId);
        log.info("Scoring pool of {} areas by baseline polyline (geom={} points)",
                new Object[]{pool.size(), baselineGeom.size()});
        if (pool.isEmpty()) {
            return baselineOnlyInfo(prefs, baseline, calibrator);
        }
        List<AreaCandidate> candidates = scoreCandidates(taskId, pool, baselineGeom);

        // Zwracamy surową pulę (całość po score) — planner/fallback zrobi selekcję.
        List<Waypoint> anchorWps = BaselineComputer.buildAnchorWaypoints(prefs);
        return new PlanningOrchestrationService.CoverageBuildInfo(anchorWps, pool.size(), candidates.size(),
                baseline.distanceKm(), calibrator.roadAreas(),
                candidates, baseline.geometry());
    }

    /** baseline pre-screen. Rzuca gdy sam baseline > budżet. */
    private BaselineComputer.BaselineProbe runBaseline(UUID taskId, RoutePreferences prefs, String profile,
                                                       RoadFactorCalibrator calibrator, int budgetKm) {
        setPhase.accept(taskId, "baseline");
        checkCancel.accept(taskId);
        long tBaselineNs = System.nanoTime();
        BaselineComputer.BaselineProbe baseline = baselineComputer.compute(prefs, profile, calibrator);
        long baselineMs = (System.nanoTime() - tBaselineNs) / 1_000_000;
        var baselineVerdict = BudgetReconciler.evaluateBaseline(baseline.distanceKm(), prefs.days(), prefs.kmPerDay());
        log.info("Baseline: dist={} km, climb={} m, anchorsStraight={} km, roadFactor(seed)={}, verdict={}",
                new Object[]{Math.round(baseline.distanceKm()), Math.round(baseline.climbM()),
                        Math.round(baseline.anchorsStraightKm()), String.format("%.2f", calibrator.roadAreas()),
                        baselineVerdict.verdict()});
        log.info("STARTUP TIMING: baseline (BRouter start→meta, liczony RAZ — reużyty w plannerze) = {} ms", baselineMs);
        if (baselineVerdict.verdict() == BudgetReconciler.Verdict.BUDGET_IMPOSSIBLE) {
            throw new PlanningSessionNotReadyException(
                    "Trasa start→via→meta sama waży " + Math.round(baseline.distanceKm()) + " km, " +
                    "Twój budżet to " + budgetKm + " km. Zwiększ dni / km na dzień albo wyrzuć via.");
        }
        return baseline;
    }

    /** Pula gmin pusta przy korytarzu bazowej → wyprawa z samych anchorów. */
    private PlanningOrchestrationService.CoverageBuildInfo baselineOnlyInfo(RoutePreferences prefs,
            BaselineComputer.BaselineProbe baseline, RoadFactorCalibrator calibrator) {
        log.warn("No areas near baseline corridor — returning baseline-only trip");
        return new PlanningOrchestrationService.CoverageBuildInfo(BaselineComputer.buildAnchorWaypoints(prefs), 0, 0,
                baseline.distanceKm(), calibrator.roadAreas(),
                new ArrayList<>(), baseline.geometry());
    }

    /** Score każdej gminy względem bazowej (detour + intersect + entry-point), sort ASC po detour. */
    private List<AreaCandidate> scoreCandidates(UUID taskId, List<UnvisitedArea> nearPool, List<double[]> baselineGeom) {
        AreaCoverageIndex idx = coverageIndexFactory.build(nearPool);
        Set<Integer> crossed = idx.visitedAreaIds(baselineGeom);
        List<AreaCandidate> candidates = new ArrayList<>(nearPool.size());
        for (UnvisitedArea a : nearPool) {
            checkCancel.accept(taskId);
            AreaCandidate c = CoverageAreaSelection.scoreAreaAgainstBaseline(a, baselineGeom, crossed.contains(a.areaId()));
            candidates.add(c);
        }
        // Sort by detourStraightKm ASC (zaliczone darmowo = 0).
        candidates.sort((x, y) -> Double.compare(x.detourStraightKm, y.detourStraightKm));
        int alreadyIntersected = (int) candidates.stream().filter(c -> c.intersected).count();
        log.info("Scored {} candidates: {} already intersected by baseline (free credits)",
                new Object[]{candidates.size(), alreadyIntersected});
        return candidates;
    }

    /** Pobierz pulę nieodwiedzonych gmin (country+level pary + special groups) z visit-service. */
    List<UnvisitedArea> fetchAreaPool(UUID taskId, RoutePreferences prefs, String bearerToken) {
        List<UnvisitedArea> pool = new ArrayList<>();
        setPhase.accept(taskId, "fetching-areas");
        checkCancel.accept(taskId);
        if (prefs.countryIds() != null && prefs.levelIds() != null) {
            int pairs = Math.min(prefs.countryIds().size(), prefs.levelIds().size());
            for (int i = 0; i < pairs; i++) {
                checkCancel.accept(taskId);
                int countryId = prefs.countryIds().get(i);
                int levelId = prefs.levelIds().get(i);
                pool.addAll(visitClient.listUnvisitedAreas(bearerToken, countryId, levelId, MAX_AREAS_TO_FETCH));
            }
        }
        if (prefs.specialGroupIds() != null) {
            Integer scopedCountryId = (prefs.countryIds() != null && prefs.countryIds().size() == 1)
                    ? prefs.countryIds().get(0) : null;
            for (Integer groupId : prefs.specialGroupIds()) {
                checkCancel.accept(taskId);
                pool.addAll(visitClient.listUnvisitedSpecialAreas(bearerToken, groupId, scopedCountryId, MAX_AREAS_TO_FETCH));
            }
        }
        if (pool.isEmpty()) {
            throw new PlanningSessionNotReadyException("no unvisited areas in scope");
        }
        return pool;
    }
}