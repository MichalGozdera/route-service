package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.ElevationProfile;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.VisitServiceClient;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.AreaCoverageIndexFactory;
import velomarker.port.out.planning.SpatialIndex;
import velomarker.port.out.planning.SpatialIndexFactory;
import java.util.Set;
import velomarker.exception.PlanningSessionNotReadyException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Budowa waypointów dla COVERAGE: pobranie puli gmin, baseline-probe, bbox-filter, scoring/density, greedy selekcja,
 * dedup i złożenie trasy roboczej (anchory + entry-pointy gmin). Wydzielone z PlanningOrchestrationService.
 */
final class CoverageWaypointBuilder {

    private static final Logger log = LoggerFactory.getLogger(CoverageWaypointBuilder.class);
    private static final int MAX_AREAS_TO_FETCH = 40000;
    private static final int DENSITY_PROBE_AREAS = 30;

    private final VisitServiceClient visitClient;
    private final ElevationDataSource elevation;
    private final CalculateRouteUseCase routeUseCase;
    private final WaypointSelector waypointSelector;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> setPhase;
    private final AreaCoverageIndexFactory coverageIndexFactory;
    private final SpatialIndexFactory spatialIndexFactory;
    private final BaselineComputer baselineComputer;

    CoverageWaypointBuilder(VisitServiceClient visitClient, ElevationDataSource elevation,
                            CalculateRouteUseCase routeUseCase, WaypointSelector waypointSelector,
                            Consumer<UUID> checkCancel, BiConsumer<UUID, String> setPhase,
                            AreaCoverageIndexFactory coverageIndexFactory, SpatialIndexFactory spatialIndexFactory) {
        this.visitClient = visitClient;
        this.elevation = elevation;
        this.routeUseCase = routeUseCase;
        this.waypointSelector = waypointSelector;
        this.checkCancel = checkCancel;
        this.setPhase = setPhase;
        this.coverageIndexFactory = coverageIndexFactory;
        this.spatialIndexFactory = spatialIndexFactory;
        this.baselineComputer = new BaselineComputer(routeUseCase, waypointSelector);
    }

    PlanningOrchestrationService.CoverageBuildInfo build(UUID taskId, RoutePreferences prefs, String bearerToken, String profile,
                                                  RoadFactorCalibrator calibrator) {
        List<UnvisitedArea> pool = fetchAreaPool(taskId, prefs, bearerToken);
        boolean loop = Boolean.TRUE.equals(prefs.loop());
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : 0;

        // ETAP 1: PRE-SCREEN BASELINE — BRouter start→via→meta = DOLNA GRANICA trasy (rzuca gdy sam > budżet).
        BaselineComputer.BaselineProbe baseline = runBaseline(taskId, prefs, profile, calibrator, budgetKm);
        List<double[]> baselineGeom = baseline.geometry();

        // ETAP 2: SNAP-TO-BASELINE — gminy wstrzeliwane mini-detorami w bazową (NIE TSP-through-centroids);
        // zaliczenie = geometria BRoutera przecina ring (wystarczy musnąć).
        setPhase.accept(taskId, "scoring-areas");
        checkCancel.accept(taskId);
        log.info("Filtering pool of {} areas by baseline polyline (geom={} points)",
                new Object[]{pool.size(), baselineGeom.size()});
        if (pool.isEmpty()) {
            return baselineOnlyInfo(prefs, baseline, calibrator);
        }
        List<AreaCandidate> candidates = scoreCandidates(taskId, pool, baselineGeom);
        densityAwareResort(candidates);

        // ETAP 3: DENSITY PROBE — BRouter na 30 najbliższych gminach do bazowej → roadAreas.
        long tProbeNs = System.nanoTime();
        densityProbeFromCandidates(taskId, candidates, baselineGeom, profile, calibrator);
        log.info("STARTUP TIMING: density-probe (BRouter ~30 obszarów) = {} ms", (System.nanoTime() - tProbeNs) / 1_000_000);

        // ETAP 4: GREEDY dobór do budżetu + pre-BRouter dedup + złożenie waypointów.
        return assembleCoverageInfo(prefs, candidates, baseline, calibrator, budgetKm, baselineGeom, loop);
    }

    /** ETAP 1: pre-screen baseline (start→via→meta) + sanity budżetu. Rzuca gdy sam baseline > budżet. */
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
                new ArrayList<>(), new ArrayList<>(), baseline.geometry());
    }

    /** ETAP 2a: score każdej gminy względem bazowej (detour + intersect + entry-point), sort ASC po detour. */
    private List<AreaCandidate> scoreCandidates(UUID taskId, List<UnvisitedArea> nearPool, List<double[]> baselineGeom) {
        // intersected = które gminy baseline JUŻ przecina (≥200m w głąb) — JEDEN przebieg JTS nad nearPool,
        // zamiast ręcznego point-in-ring ±300 per gmina. Index budowany PO bboxie (tylko gminy przy korytarzu).
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

    /**
     * ETAP 2b: DENSITY-AWARE RE-SORT (regret-style diversity). Greedy ASC po detour bierze najtańsze z
     * gęstego klastra ZANIM dotrze do droższych izolowanych → pętle wokół jednego klastra + brak pokrycia
     * dalekich rejonów. Fix: zlicz numNearby per candidate (promień 5 km) i re-sort po
     * effectiveCost = detour × (1 + numNearby × 0.1). Izolowane (numNearby≈0) zachowują detour; gęste
     * klastry dostają mnożnik > 1 → idą później. Mutuje {@code candidates} in-place.
     */
    private void densityAwareResort(List<AreaCandidate> candidates) {
        final double NEARBY_RADIUS_KM = 5.0;
        final double DENSITY_PENALTY = 0.1;
        int[] numNearby = new int[candidates.size()];
        // Siatka spatial po NIE-przeciętych kandydatach — O(n) zamiast O(n²) (kluczowe dla 35000 komun).
        // Przecięte (intersected) zostają z numNearby=0, jak w oryginale (były pomijane przez `continue`).
        List<Integer> activeIdx = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!candidates.get(i).intersected) activeIdx.add(i);
        }
        if (!activeIdx.isEmpty()) {
            double[][] pts = new double[activeIdx.size()][];
            for (int t = 0; t < activeIdx.size(); t++) {
                AreaCandidate c = candidates.get(activeIdx.get(t));
                pts[t] = new double[]{c.area.lng(), c.area.lat()};
            }
            SpatialIndex grid = spatialIndexFactory.build(pts);
            for (int t = 0; t < activeIdx.size(); t++) {
                numNearby[activeIdx.get(t)] = grid.countWithinKm(t, NEARBY_RADIUS_KM);
            }
        }
        // Sortuj przez wrapper (nie psujemy immutability AreaCandidate).
        record ScoredCandidate(AreaCandidate c, double effectiveCost, boolean intersected) {}
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            AreaCandidate c = candidates.get(i);
            double effCost = c.intersected
                    ? 0
                    : c.detourStraightKm * (1.0 + numNearby[i] * DENSITY_PENALTY);
            scored.add(new ScoredCandidate(c, effCost, c.intersected));
        }
        scored.sort((a, b) -> {
            if (a.intersected && !b.intersected) return -1;
            if (!a.intersected && b.intersected) return 1;
            return Double.compare(a.effectiveCost, b.effectiveCost);
        });
        candidates.clear();
        for (ScoredCandidate s : scored) candidates.add(s.c);
        long totalNearby = 0;
        int maxNearby = 0;
        int nonIntersected = 0;
        for (int i = 0; i < numNearby.length; i++) {
            if (candidates.get(i).intersected) continue;
            totalNearby += numNearby[i];
            if (numNearby[i] > maxNearby) maxNearby = numNearby[i];
            nonIntersected++;
        }
        long avgNearby = nonIntersected > 0 ? totalNearby / nonIntersected : 0;
        log.info("Density-aware sort: avg numNearby={}, max={}, radius={}km, penalty={}",
                new Object[]{avgNearby, maxNearby, NEARBY_RADIUS_KM, DENSITY_PENALTY});
    }

    /**
     * ETAP 4: GREEDY dobór gmin aż suma detourReal ≈ surplus × 1.0 (BudgetReconciler dalej TRIM-uje
     * overshoot), pre-BRouter dedup mutual-coverage (flaguje, nie usuwa), sort po pozycji wzdłuż bazowej,
     * interleave anchors↔entry-pointy gmin → finalny CoverageBuildInfo.
     */
    private PlanningOrchestrationService.CoverageBuildInfo assembleCoverageInfo(RoutePreferences prefs,
            List<AreaCandidate> candidates, BaselineComputer.BaselineProbe baseline,
            RoadFactorCalibrator calibrator, int budgetKm, List<double[]> baselineGeom, boolean loop) {
        CoverageGreedyPicker.PickResult pr = CoverageGreedyPicker.pick(candidates, budgetKm, baseline.distanceKm(), calibrator.roadAreas(), spatialIndexFactory);
        List<AreaCandidate> picked = pr.picked();
        List<AreaCandidate> reserve = pr.reserve();

        // PRE-BROUTER DEDUP: flaguje (NIE usuwa z listy) obszary których entry-points są w ringach
        // sąsiadów. Iter 9 Fix #1: zostają w picked list (= raportowane), tylko nie dostaja wp w tour.
        picked = CoverageAreaSelection.dedupByMutualCoverage(picked);
        long mutuallyCoveredCount = picked.stream().filter(AreaCandidate::isMutuallyCoveredByNeighbor).count();
        if (mutuallyCoveredCount > 0) {
            log.info("Pre-BRouter mutual dedup: {} flagged as mutually-covered (zostają w picked, bez explicit wp)",
                    new Object[]{mutuallyCoveredCount});
        }

        // Sort picked by insertionIdx → naturalna kolejność wzdłuż bazowej.
        picked.sort((x, y) -> Integer.compare(x.insertionIdx, y.insertionIdx));

        List<Waypoint> result = interleaveWaypoints(prefs, picked, baselineGeom, loop);

        int anchorCount = result.size() - (int) picked.stream().filter(c -> !c.isMutuallyCoveredByNeighbor()).count();
        log.info("Final waypoints: {} (= {} anchors + {} gminy entry-points)",
                new Object[]{result.size(), anchorCount, picked.size()});
        logCategoryBreakdown(picked.stream().map(c -> c.area).toList());

        return new PlanningOrchestrationService.CoverageBuildInfo(result, candidates.size(), picked.size(),
                baseline.distanceKm(), calibrator.roadAreas(),
                picked, reserve, baseline.geometry());
    }



    /** Złóż finalną listę waypointów: anchory (start→via→meta) + entry-pointy wybranych gmin wstawione
     *  między anchory wg insertionIdx (pomija gminy oznaczone jako mutually-covered — trasa je naturalnie minie). */
    private List<Waypoint> interleaveWaypoints(RoutePreferences prefs, List<AreaCandidate> picked,
                                               List<double[]> baselineGeom, boolean loop) {
        // ETAP 5: BUILD WAYPOINTS — wstaw entry pointy MIĘDZY anchors, w kolejności insertionIdx.
        // Znajdź indeks w baselineGeom dla każdego anchor'a (via, meta), żeby umieścić gminy
        // we właściwym segmencie bazowej.
        List<Waypoint> anchorWps = new ArrayList<>();
        anchorWps.add(prefs.start());
        if (prefs.via() != null) anchorWps.addAll(prefs.via());
        if (loop) anchorWps.add(prefs.start());
        else if (prefs.end() != null) anchorWps.add(prefs.end());
        else anchorWps.add(prefs.start());

        int[] anchorIndices = new int[anchorWps.size()];
        for (int i = 0; i < anchorWps.size(); i++) {
            anchorIndices[i] = PlanningGeom.findNearestGeomIdx(baselineGeom, anchorWps.get(i).toLngLat());
        }

        List<Waypoint> result = new ArrayList<>();
        result.add(anchorWps.get(0)); // start
        int pickedPtr = 0;
        for (int ai = 1; ai < anchorWps.size(); ai++) {
            int anchorIdx = anchorIndices[ai];
            while (pickedPtr < picked.size() && picked.get(pickedPtr).insertionIdx <= anchorIdx) {
                AreaCandidate c = picked.get(pickedPtr);
                // Iter 9 Fix #1: SKIP mutually-covered (trasa BRouter naturalnie przejdzie)
                if (!c.isMutuallyCoveredByNeighbor()) {
                    result.add(new Waypoint(c.entryLng, c.entryLat, c.area.name()));
                }
                pickedPtr++;
            }
            result.add(anchorWps.get(ai));
        }
        // Edge case: gminy z insertionIdx > ostatni anchor idx — dolepiamy po mecie (nie powinno się zdarzyć).
        while (pickedPtr < picked.size()) {
            AreaCandidate c = picked.get(pickedPtr);
            if (!c.isMutuallyCoveredByNeighbor()) {
                result.add(new Waypoint(c.entryLng, c.entryLat, c.area.name()));
            }
            pickedPtr++;
        }
        return result;
    }


    /** Pobierz pulę nieodwiedzonych gmin (country+level pary + special groups) z visit-service. */
    private List<UnvisitedArea> fetchAreaPool(UUID taskId, RoutePreferences prefs, String bearerToken) {
        List<UnvisitedArea> pool = new ArrayList<>();
        setPhase.accept(taskId, "fetching-areas");
        checkCancel.accept(taskId);
        // countryIds[i] + levelIds[i] = i-ta PARA. NIE cartesian product — user wybiera per kraj
        // jeden poziom (PL=gmina, DE=Kreis), różne kraje mogą mieć inne poziomy.
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


    /** Density probe z 30 najbliższych kandydatów (do bazowej). Daje calibrator.roadAreas(). */
    private void densityProbeFromCandidates(UUID taskId, List<AreaCandidate> sortedCandidates,
                                            List<double[]> baselineGeom, String profile,
                                            RoadFactorCalibrator calibrator) {
        if (sortedCandidates.isEmpty() || baselineGeom.size() < 2) return;
        setPhase.accept(taskId, "density-probe");
        checkCancel.accept(taskId);
        int n = Math.min(DENSITY_PROBE_AREAS, sortedCandidates.size());
        // Sample 30 NAJBLIŻSZYCH — to są te które mają realne szanse trafić do final puli.
        List<AreaCandidate> sample = new ArrayList<>(sortedCandidates.subList(0, n));
        sample.sort((x, y) -> Integer.compare(x.insertionIdx, y.insertionIdx));
        List<double[]> probeWp = new ArrayList<>(sample.size() + 2);
        probeWp.add(baselineGeom.get(0));
        for (AreaCandidate c : sample) probeWp.add(new double[]{c.entryLng, c.entryLat});
        probeWp.add(baselineGeom.get(baselineGeom.size() - 1));
        double straight = waypointSelector.straightLineDistanceKm(probeWp);
        try {
            RouteCalculation r = routeUseCase.calculate(
                    new CalculateRouteUseCase.CalculateRouteCommand(probeWp, profile, false));
            calibrator.measure(r.distanceKm(), straight); // dokładniejszy niż seed: nadpisuje szkielet
            log.info("Density probe: {} areas, real={} km, straight={} km → roadAreas={}",
                    new Object[]{sample.size(), Math.round(r.distanceKm()), Math.round(straight),
                            String.format("%.2f", calibrator.roadAreas())});
        } catch (RuntimeException e) {
            log.warn("Density probe failed ({}), keeping baseline-seed roadAreas={}",
                    e.getMessage(), String.format("%.2f", calibrator.roadAreas()));
        }
    }



    /** Log per-category breakdown (kraj × poziom/special) — żeby user widział czemu wybrano takie obszary. */
    private static void logCategoryBreakdown(List<UnvisitedArea> finalOrder) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UnvisitedArea a : finalOrder) {
            String key = a.specialGroupId() != null
                    ? "C" + a.countryId() + "/sg" + a.specialGroupId()
                    : "C" + a.countryId() + "/L" + a.levelId();
            counts.merge(key, 1, Integer::sum);
        }
        log.info("Category breakdown after reconcile: {}", counts);
    }


}
