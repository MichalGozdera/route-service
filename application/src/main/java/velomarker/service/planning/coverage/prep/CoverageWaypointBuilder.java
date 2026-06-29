package velomarker.service.planning.coverage.prep;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.ElevationProfile;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.planning.AreaPool;
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

// Przygotowanie surowej puli gmin dla COVERAGE intent: pobranie nieodwiedzonych, baseline pre-screen, JTS scoring.
public final class CoverageWaypointBuilder {

    private static final Logger log = LoggerFactory.getLogger(CoverageWaypointBuilder.class);
    private static final int MAX_AREAS_TO_FETCH = 40000;
    /** Promień walidacji startowej: jeśli ŻADNA gmina nie leży bliżej niż tyle km od korytarza
     *  start→meta → błąd (user poprawia input). NIE jest to filtr puli — gminy dalej niż tyle NIE
     *  są wycinane; o ich dobraniu decyduje budżet w pętli wzrostu (bramka skoku w seedzie). */
    private static final double PRESENCE_RADIUS_KM = 200.0;

    private final VisitServiceClient visitClient;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> setPhase;
    private final AreaCoverageIndexFactory coverageIndexFactory;
    private final BaselineComputer baselineComputer;

    public CoverageWaypointBuilder(VisitServiceClient visitClient,
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

    public CoverageBuildInfo build(UUID taskId, RoutePreferences prefs, String bearerToken, String profile, PlanTimings timings) {
        AreaPool areaPool = fetchAreaPool(taskId, prefs, bearerToken);
        List<UnvisitedArea> pool = areaPool.unvisited();
        List<UnvisitedArea> historicallyVisited = areaPool.visited();
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : 0;

        BaselineProbe baseline = runBaseline(taskId, prefs, profile, budgetKm, timings);
        List<double[]> baselineGeom = baseline.geometry();

        setPhase.accept(taskId, "scoring-areas");
        checkCancel.accept(taskId);
        log.info("Scoring pool of {} areas by baseline polyline (geom={} points)",
                new Object[]{pool.size(), baselineGeom.size()});
        if (pool.isEmpty()) {
            return baselineOnlyInfo(prefs, baseline);
        }
        List<AreaCandidate> candidates = scoreCandidates(taskId, pool, baselineGeom);

        // Walidacja obecności: jeśli ŻADNA gmina nie leży w promieniu PRESENCE_RADIUS_KM od korytarza
        // → komunikat „regiony za daleko" PRZED trasowaniem BRouterem (user poprawia input). W przeciwnym
        // razie przepuszczamy CAŁĄ pulę — o dobraniu dalekich gmin decyduje budżet w pętli wzrostu seeda.
        validatePresence(candidates);

        List<Waypoint> anchorWps = BaselineComputer.buildAnchorWaypoints(prefs);
        return new CoverageBuildInfo(anchorWps, candidates, historicallyVisited, baseline.geometry());
    }

    /** Walidacja startowa (NIE filtr): jeśli NIC nie leży ≤ {@link #PRESENCE_RADIUS_KM} od korytarza
     *  start→meta → komunikat „regiony za daleko" + {@code AreasTooFarException} (przed BRouterem). */
    private void validatePresence(List<AreaCandidate> candidates) {
        double nearest = candidates.stream().mapToDouble(CoverageWaypointBuilder::corridorDistanceKm).min().orElse(Double.MAX_VALUE);
        if (nearest > PRESENCE_RADIUS_KM) {
            log.info("Coverage: regiony za daleko — najbliższy region {} km od korytarza (próg {} km)",
                    new Object[]{Math.round(nearest), Math.round(PRESENCE_RADIUS_KM)});
            throw new velomarker.exception.AreasTooFarException();
        }
    }

    /** Jednostronna odległość regionu od korytarza start→meta (km). Region przecięty przez baseline = 0.
     *  {@code detourStraightKm} to round-trip (2·minDist+0.2), więc dzielimy przez 2. */
    private static double corridorDistanceKm(AreaCandidate c) {
        return c.intersected ? 0 : Math.max(0, (c.detourStraightKm - 0.2) / 2.0);
    }

    private BaselineProbe runBaseline(UUID taskId, RoutePreferences prefs, String profile,
                                                       int budgetKm, PlanTimings timings) {
        setPhase.accept(taskId, "baseline");
        checkCancel.accept(taskId);
        long tBaselineNs = System.nanoTime();
        BaselineProbe baseline = baselineComputer.compute(prefs, profile);
        long baselineMs = (System.nanoTime() - tBaselineNs) / 1_000_000;
        timings.addBaselineMs(baselineMs);
        var baselineVerdict = BudgetReconciler.evaluateBaseline(baseline.distanceKm(), prefs.days(), prefs.kmPerDay());
        log.info("Baseline: dist={} km, climb={} m, anchorsStraight={} km, verdict={}",
                new Object[]{Math.round(baseline.distanceKm()), Math.round(baseline.climbM()),
                        Math.round(baseline.anchorsStraightKm()), baselineVerdict.verdict()});
        log.info("STARTUP TIMING: baseline (BRouter start→meta, liczony RAZ — reużyty w plannerze) = {} ms", baselineMs);
        if (baselineVerdict.verdict() == Verdict.BUDGET_IMPOSSIBLE) {
            throw new PlanningSessionNotReadyException(
                    "Trasa start→via→meta sama waży " + Math.round(baseline.distanceKm()) + " km, " +
                    "Twój budżet to " + budgetKm + " km. Zwiększ dni / km na dzień albo wyrzuć via.");
        }
        return baseline;
    }

    private CoverageBuildInfo baselineOnlyInfo(RoutePreferences prefs,
            BaselineProbe baseline) {
        log.warn("No areas near baseline corridor — returning baseline-only trip");
        return new CoverageBuildInfo(BaselineComputer.buildAnchorWaypoints(prefs),
                new ArrayList<>(), new ArrayList<>(), baseline.geometry());
    }

    private List<AreaCandidate> scoreCandidates(UUID taskId, List<UnvisitedArea> nearPool, List<double[]> baselineGeom) {
        AreaCoverageIndex idx = coverageIndexFactory.build(nearPool);
        Set<Integer> crossed = idx.visitedAreaIds(baselineGeom);
        List<AreaCandidate> candidates = new ArrayList<>(nearPool.size());
        for (UnvisitedArea a : nearPool) {
            checkCancel.accept(taskId);
            AreaCandidate c = CoverageAreaSelection.scoreAreaAgainstBaseline(a, baselineGeom, crossed.contains(a.areaId()));
            candidates.add(c);
        }
        candidates.sort((x, y) -> Double.compare(x.detourStraightKm, y.detourStraightKm));
        int alreadyIntersected = (int) candidates.stream().filter(c -> c.intersected).count();
        log.info("Scored {} candidates: {} already intersected by baseline (free credits)",
                new Object[]{candidates.size(), alreadyIntersected});
        return candidates;
    }

    public AreaPool fetchAreaPool(UUID taskId, RoutePreferences prefs, String bearerToken) {
        List<UnvisitedArea> pool = new ArrayList<>();
        List<UnvisitedArea> visited = new ArrayList<>();
        setPhase.accept(taskId, "fetching-areas");
        checkCancel.accept(taskId);
        if (prefs.countryIds() != null && prefs.levelIds() != null) {
            int pairs = Math.min(prefs.countryIds().size(), prefs.levelIds().size());
            for (int i = 0; i < pairs; i++) {
                checkCancel.accept(taskId);
                int countryId = prefs.countryIds().get(i);
                int levelId = prefs.levelIds().get(i);
                AreaPool ap = visitClient.listAreaPool(bearerToken, countryId, levelId, MAX_AREAS_TO_FETCH);
                pool.addAll(ap.unvisited());
                visited.addAll(ap.visited());
            }
        }
        if (prefs.specialGroupIds() != null) {
            Integer scopedCountryId = (prefs.countryIds() != null && prefs.countryIds().size() == 1)
                    ? prefs.countryIds().get(0) : null;
            for (Integer groupId : prefs.specialGroupIds()) {
                checkCancel.accept(taskId);
                AreaPool ap = visitClient.listSpecialAreaPool(bearerToken, groupId, scopedCountryId, MAX_AREAS_TO_FETCH);
                pool.addAll(ap.unvisited());
                visited.addAll(ap.visited());
            }
        }
        if (pool.isEmpty()) {
            if (!visited.isEmpty()) {
                throw new velomarker.exception.AllAreasVisitedException();
            }
            throw new PlanningSessionNotReadyException("no unvisited areas in scope");
        }
        return new AreaPool(pool, visited);
    }
}
