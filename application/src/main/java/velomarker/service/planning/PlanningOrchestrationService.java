package velomarker.service.planning;

import async.TaskCancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.ElevationProfile;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.PlanningIntent;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.PlanningSummary;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.exception.PlanningSessionMissingException;
import velomarker.exception.PlanningSessionNotReadyException;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.out.BrouterRoutingClient;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.AreaCoverageIndexFactory;
import velomarker.port.out.planning.SpatialIndexFactory;
import velomarker.port.out.planning.PlanningSessionDayRepository;
import velomarker.port.out.planning.PlanningSessionRepository;
import velomarker.port.out.planning.VisitServiceClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Główny mózg asystenta. Bierze sesję usera + preferences + intent → produkuje listę dni.
 *
 * <p>Etapy (każdy raportuje phase + sprawdza cancel):
 * <ol>
 *   <li>Walidacja preferences dla intentu.</li>
 *   <li>(COVERAGE) Pobranie nieodwiedzonych z visit-service, budowa puli, ordering TSP, snap.</li>
 *   <li>Probe BRouter dla road factor calibration.</li>
 *   <li>Pełny route (BRouter z elevation enrichment).</li>
 *   <li>DaySplitter — podział geometrii na N dni.</li>
 *   <li>Per-dzień: metryki + zapis PlanningSessionDay.</li>
 * </ol>
 */
public class PlanningOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PlanningOrchestrationService.class);


    static final int DEFAULT_KM_PER_DAY = 100;

    /** Max gap (km) między dowolnym anchor'em a najbliższym punktem geometrii BRouter. Powyżej → FAIL. */
    static final double ANCHOR_REACHABILITY_KM = 2.0;
    /**
     * MIN liczba sample elevation profile UZYWANA do DaySplitter (wyzsza niz default 500 dla UI).
     * 2000 = ~0.85 km/sample dla 1700 km trasy. Bez tego: 500 sample = 3.4 km/sample, gubi gorki,
     * split-time effort != real per-day effort -> dni nierowne (1.38x spread w teście usera).
     * Liczba ADAPTYWNA: dla N dni potrzebujemy ≥ N punktów (jeden na granicę dnia), praktycznie N×3
     * dla czystego splittingu. Przy 3650 dniach default 2000 by zostawił 1650 pustych dni (boundary
     * loop wybiega poza profil). Cap 30000 jako sanity.
     */
    static final int DAY_SPLIT_ELEVATION_SAMPLES = 2000;
    /** Górny cap dla adaptywnego sample count. 200000 = 1.15 km/sample dla Francji (229000 km), pełna
     *  geometria dla regionalnych tras Pardubice-typu (~50k coords). Memory ~3.2 MB profile array. */
    static final int DAY_SPLIT_ELEVATION_SAMPLES_CAP = 200000;
    /** Próg climb warning względem refClimbTotal — nie blokuje verdict, tylko user-facing flag. */
    static final double CLIMB_WARNING_RATIO = 1.10;

    private final PlanningSessionRepository sessionRepository;
    private final PlanningSessionDayRepository dayRepository;
    private final VisitServiceClient visitClient;
    private final CalculateRouteUseCase routeUseCase;
    private final BrouterRoutingClient brouterClient; // v3.16: reset liczników „cumulative" per plan
    private final AreaCoverageIndexFactory coverageIndexFactory; // v3.18: per-dzień covered area IDs
    private final SpatialIndexFactory spatialIndexFactory;
    private final ElevationDataSource elevation;
    private final WaypointSelector waypointSelector;
    private final DaySplitter daySplitter;
    /** Planner pokrycia (seed + compact-loop) — COVERAGE intent. */
    private final velomarker.service.planning.coverage.CoveragePlanner coveragePlanner;
    private PlanProgressSink progressSink; // setter-injected by SpringAppConfig (avoids cyclic dep)

    /**
     * Rejestr brakujących tile'ów BRoutera (.rd5) per task. BRouter zwraca 400
     * {@code datafile X.rd5 not found} gdy tile DEM dla regionu nie jest pobrane. Algorytm zbiera
     * te nazwy podczas calculateChunkResilient i raportuje w Plan summary z gotową komendą wget,
     * by user nie szukał wśród setek warnów „target-island".
     */
    private final java.util.concurrent.ConcurrentMap<UUID, java.util.Set<String>> missingTilesPerTask =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PlanningOrchestrationService(PlanningSessionRepository sessionRepository,
                                        PlanningSessionDayRepository dayRepository,
                                        VisitServiceClient visitClient,
                                        CalculateRouteUseCase routeUseCase,
                                        BrouterRoutingClient brouterClient,
                                        AreaCoverageIndexFactory coverageIndexFactory,
                                        SpatialIndexFactory spatialIndexFactory,
                                        ElevationDataSource elevation,
                                        WaypointSelector waypointSelector,
                                        DaySplitter daySplitter,
                                        velomarker.service.planning.coverage.CoveragePlanner coveragePlanner) {
        this.sessionRepository = sessionRepository;
        this.dayRepository = dayRepository;
        this.visitClient = visitClient;
        this.routeUseCase = routeUseCase;
        this.brouterClient = brouterClient;
        this.coverageIndexFactory = coverageIndexFactory;
        this.spatialIndexFactory = spatialIndexFactory;
        this.elevation = elevation;
        this.waypointSelector = waypointSelector;
        this.daySplitter = daySplitter;
        this.coveragePlanner = coveragePlanner;
        log.info("PlanningOrchestrationService initialized (coverage planner {})",
                coveragePlanner != null ? "present" : "absent");
    }

    /** Setter injection — wstrzykiwane PO utworzeniu obu beanów (rozwiązuje cykl OrchestrationService ↔ PlanTaskService). */
    public void setProgressSink(PlanProgressSink sink) {
        this.progressSink = sink;
    }

    /**
     * Główna metoda wywoływana z PlanTaskService (virtual thread).
     *
     * @param taskId       PlanTask.id (do cancel checkpointów i raportowania postępu)
     * @param userId       właściciel sesji
     * @param bearerToken  JWT do propagacji visit-service
     */
    public void executePlan(UUID taskId, UUID userId, String bearerToken) {
        missingTilesPerTask.put(taskId, java.util.concurrent.ConcurrentHashMap.newKeySet());
        try {
            PlanningSession session = loadValidatedSession(userId);
            RoutePreferences prefs = session.preferences();

            setPhase(taskId, "validating");
            checkCancel(taskId);

            String profile = resolveProfile(prefs);
            resetPlanCounters();
            logPlanStart(taskId, session, profile, prefs);
            RoadFactorCalibrator calibrator = new RoadFactorCalibrator();

            WaypointBuild wb = buildWaypointsForIntent(taskId, session, prefs, profile, bearerToken, calibrator);
            RoutedPlan routed = routePlan(taskId, prefs, wb, profile);
            RouteCalculation full = routed.full();
            List<Waypoint> allWaypoints = routed.finalWaypoints();
            // Stats per-day slicing działa czysto: planner sam robi final recompute z computeStats=true
            // przed return (zob. BrouterFn powyżej), więc `full.stats()` ma spans z pełnej trasy.

            setPhase(taskId, "sampling-elevation");
            checkCancel(taskId);
            ElevationProfile fullProfile = sampleElevationForSplit(prefs, full);

            setPhase(taskId, "splitting-days");
            checkCancel(taskId);
            List<DaySplitter.DayBoundary> boundaries = daySplitter.splitIntoDays(
                    fullProfile, prefs.days(), prefs.kmPerDay(), prefs.elevationPerDayM(), profile);

            List<PlanningSessionDay> days = new DayBuilder(elevation, coverageIndexFactory, spatialIndexFactory, this::checkCancel, this::setPhase).build(taskId, session, prefs, profile, full, fullProfile, boundaries, allWaypoints, wb.coverageInfo());

            checkCancel(taskId);
            setPhase(taskId, "saving");
            saveAndSummarize(taskId, userId, session, days, prefs, wb.coverageInfo(), full);
        } finally {
            reportMissingTiles(taskId);
        }
    }

    /** Wczytuje sesję usera i waliduje: intent ustawiony + preferences kompletne dla intentu. */
    private PlanningSession loadValidatedSession(UUID userId) {
        PlanningSession session = sessionRepository.findByUserId(userId)
                .orElseThrow(() -> new PlanningSessionMissingException(userId));
        if (session.intent() == null) {
            throw new PlanningSessionMissingException("Intent not set");
        }
        if (!session.preferences().isReadyToCalculate(session.intent())) {
            throw new PlanningSessionNotReadyException("required fields missing for intent " + session.intent());
        }
        return session;
    }

    /** Reset liczników „cumulative" per plan (nie od startu serwisu): BRouter (v3.16) + elevation enrichment (v3.17). */
    private void resetPlanCounters() {
        brouterClient.resetPlanCounters();
        routeUseCase.resetPlanCounters();
    }

    /** Parametry wejściowe planu — jedna linia na starcie (diagnostyka: z czym wystartował dany run). */
    private void logPlanStart(UUID taskId, PlanningSession session, String profile, RoutePreferences prefs) {
        Waypoint startWp = prefs.start();
        log.info("PLAN START: task={} intent={} profile={} days={} kmPerDay={} elevPerDay={} start={} via={} loop={}",
                new Object[]{taskId, session.intent(), profile, prefs.days(), prefs.kmPerDay(),
                        prefs.elevationPerDayM(),
                        startWp == null ? "null" : String.format(java.util.Locale.ROOT, "%.5f,%.5f", startWp.lng(), startWp.lat()),
                        prefs.via() == null ? 0 : prefs.via().size(), prefs.loop()});
    }

    /** Routing BRouter: COVERAGE bierze gotowy wynik plannera (bypass TRIM/GROW/DEDUP), AB/FREESTYLE/fallback przez RouteTracer. */
    private RoutedPlan routePlan(UUID taskId, RoutePreferences prefs, WaypointBuild wb, String profile) {
        setPhase(taskId, "routing-brouter");
        checkCancel(taskId);
        var coverageResult = wb.coverageResult();
        if (coverageResult != null) {
            RouteCalculation full = coverageResult.calc();
            log.info("Coverage planner bypass: actualKm={} visited={} brouterCalls={} accepted={}",
                    new Object[]{Math.round(full.distanceKm()), coverageResult.visited().size(),
                            coverageResult.brouterCalls(), coverageResult.accepted()});
            return new RoutedPlan(full, coverageResult.finalWaypoints());
        }
        // AB / FREESTYLE / coverage-fallback: BRouter chunked + trim loop (max 8 prób)
        TraceResult traced = new RouteTracer(chunkedRouter(), waypointSelector, this::checkCancel, taskId, prefs, wb.allWaypoints(), wb.coverageInfo(), profile, ANCHOR_REACHABILITY_KM).trace();
        return new RoutedPlan(traced.calc(), traced.finalWaypoints());
    }

    /** Wynik routingu: policzona trasa + finalna lista waypointów (po bypass plannera lub trace). */
    private record RoutedPlan(RouteCalculation full, List<Waypoint> finalWaypoints) {}

    /**
     * Próbkuje elevation dla DaySplitter w PODWYŻSZONEJ rozdzielczości (≥{@value #DAY_SPLIT_ELEVATION_SAMPLES},
     * nie default 500 dla UI). 500 sample/1700 km = 3.4 km/sample gubi górki → dni nierówne effort'em.
     * Bierzemy MIN(coords, CAP): splitter widzi każdy coord BRoutera z elevation; cap dla mega-tras Francji.
     */
    private ElevationProfile sampleElevationForSplit(RoutePreferences prefs, RouteCalculation full) {
        int splitSamples = Math.min(DAY_SPLIT_ELEVATION_SAMPLES_CAP,
                Math.max(DAY_SPLIT_ELEVATION_SAMPLES,
                        Math.max(prefs.days() != null ? prefs.days() * 3 : 0, full.coordinates().size())));
        return elevation.sample(full.coordinates(), splitSamples);
    }

    /** Zapisuje dni + summary sesji i loguje rozkład budżetu oraz effort per-dzień. */
    private void saveAndSummarize(UUID taskId, UUID userId, PlanningSession session, List<PlanningSessionDay> days,
                                  RoutePreferences prefs, CoverageBuildInfo coverageInfo, RouteCalculation full) {
        dayRepository.replaceAll(session.id(), days);
        session.setSummary(buildSummary(days, prefs, coverageInfo, full.distanceKm()));
        sessionRepository.save(session);
        logPlanSummary(userId, taskId, session.summary());
        logDayDistribution(days);
    }

    private void logPlanSummary(UUID userId, UUID taskId, PlanningSummary s) {
        log.info("Plan summary user={} task={} totalKm={} elev={} budget={} verdict={} pool={} (initial={})",
                new Object[]{userId, taskId,
                        Math.round(s.totalDistanceKm()), s.totalElevationGain(),
                        s.budgetKm(), s.verdict(),
                        s.poolSize(), s.initialPoolSize()});
    }

    /**
     * Effort per dzień = km + 0.1 × climb_m (alpha jak Coverage). Pokazuje czy DaySplitter dobrze
     * wyrównuje. Idealnie wszystkie dni ≈ totalEffort/N. Spread > 5% = sygnał problemu.
     */
    private void logDayDistribution(List<PlanningSessionDay> days) {
        StringBuilder dayDump = new StringBuilder();
        for (PlanningSessionDay d : days) {
            double km = d.distanceKm() != null ? d.distanceKm() : 0.0;
            int climb = d.elevationGain() != null ? d.elevationGain() : 0;
            double effort = km + 0.1 * climb;
            dayDump.append(String.format(java.util.Locale.ROOT, " d%d=%.1fkm/%dm/eff%.0f",
                    d.dayNumber(), km, climb, effort));
        }
        log.info("Day distribution:{}", dayDump);
    }

    /** Buduje waypointy wg intentu (AB/FREESTYLE/COVERAGE); dla COVERAGE odpala planner pokrycia. */
    private WaypointBuild buildWaypointsForIntent(UUID taskId, PlanningSession session, RoutePreferences prefs,
                                                  String profile, String bearerToken, RoadFactorCalibrator calibrator) {
            CoverageBuildInfo coverageInfo = null;
            List<Waypoint> allWaypoints;
            // COVERAGE BRANCH: planner pokrycia (seed + compact-loop) ZWRACA gotowy RouteCalculation,
            // pomijamy routeWithTrimAndDedup poniżej. Greedy fallback tylko gdy planner zwróci null.
            velomarker.service.planning.coverage.CoveragePlanner.CoverageResult coverageResult = null;
            switch (session.intent()) {
                case AB -> allWaypoints = buildAbWaypoints(prefs);
                case FREESTYLE -> allWaypoints = buildFreestyleWaypoints(prefs);
                case COVERAGE -> {
                    coverageInfo = new CoverageWaypointBuilder(visitClient, waypointSelector, this::checkCancel, this::setPhase, coverageIndexFactory, routeUseCase).build(taskId, prefs, bearerToken, profile, calibrator);
                    allWaypoints = coverageInfo.waypoints;
                    if (coveragePlanner != null) {
                        setPhase(taskId, "coverage-planning");
                        checkCancel(taskId);
                        // Jeden router z flagą computeStats: wewnętrzne ~10k calle plannera idą z false
                        // (dystans+geometria), finalny recompute z true (surface/road/spans dla per-day slicing).
                        velomarker.service.planning.coverage.BrouterFn brouter =
                                (wps, prof, stats) -> chunkedRouter().route(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, stats);
                        // Pool = wszystkie areas z bbox-filtered coverageInfo (planner sam selekcjonuje)
                        List<UnvisitedArea> coveragePool = new ArrayList<>();
                        if (coverageInfo.pickedCandidates() != null) {
                            for (AreaCandidate c : coverageInfo.pickedCandidates()) coveragePool.add(c.area);
                        }
                        log.info("Coverage planner: pool={} areas (z greedy bbox-filtered)", coveragePool.size());
                        coverageResult = coveragePlanner.plan(taskId, coveragePool, coverageInfo.baselineGeometry(), prefs, profile,
                                brouter, brouterClient::setSnapLogging, this::checkCancel);
                        if (coverageResult != null) {
                            log.info("Coverage planner: visited={} iters={} brouterCalls={}",
                                    new Object[]{coverageResult.visited().size(),
                                            coverageResult.iterations(), coverageResult.brouterCalls()});
                            allWaypoints = coverageResult.finalWaypoints();
                        } else {
                            log.warn("Coverage planner returned null -- fallback do greedy");
                        }
                    }
                }
                default -> throw new IllegalStateException("Unknown intent: " + session.intent());
            }
        return new WaypointBuild(allWaypoints, coverageInfo, coverageResult);
    }

    /** Wynik budowy waypointów: trasa robocza + info pokrycia (gdy COVERAGE) + gotowy wynik plannera (lub null). */
    private record WaypointBuild(List<Waypoint> allWaypoints, CoverageBuildInfo coverageInfo,
                                velomarker.service.planning.coverage.CoveragePlanner.CoverageResult coverageResult) {}

    /** Log brakujących DEM-tile BRoutera dla taska + gotowa komenda wget do pobrania (diagnostyka). */
    private void reportMissingTiles(UUID taskId) {
        java.util.Set<String> missing = missingTilesPerTask.remove(taskId);
        if (missing == null || missing.isEmpty()) return;
        java.util.List<String> sorted = new java.util.ArrayList<>(missing);
        java.util.Collections.sort(sorted);
        StringBuilder wgetCmd = new StringBuilder("cd brouter-deploy && ");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) wgetCmd.append(" && ");
            wgetCmd.append("wget http://brouter.de/brouter/segments4/").append(sorted.get(i)).append(".rd5");
        }
        log.warn("⚠ BRouter brakujące tile DEM ({}): {}", sorted.size(), sorted);
        log.warn("   Pobierz brakujące: {}", wgetCmd);
    }


    private List<Waypoint> buildAbWaypoints(RoutePreferences prefs) {
        List<Waypoint> wps = new ArrayList<>();
        wps.add(prefs.start());
        if (prefs.via() != null) wps.addAll(prefs.via());
        wps.add(prefs.end());
        return wps;
    }

    private List<Waypoint> buildFreestyleWaypoints(RoutePreferences prefs) {
        List<Waypoint> wps = new ArrayList<>();
        wps.add(prefs.start());
        if (prefs.via() != null) wps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) {
            wps.add(prefs.start());
        } else if (prefs.end() != null) {
            wps.add(prefs.end());
        }
        return wps;
    }

    /** Wynik budowania pul COVERAGE — waypointy + diagnostyka pul/baseline (do PlanningSummary). */
    public record CoverageBuildInfo(
            List<Waypoint> waypoints,
            int initialPoolSize,
            int finalPoolSize,
            Double baselineKm,
            Double roadAreas,
            List<AreaCandidate> pickedCandidates,
            List<double[]> baselineGeometry
    ) {}

    /** Buduje PlanningSummary z policzonych dni + diagnostyki pul/baseline (gdy COVERAGE). */
    private static PlanningSummary buildSummary(List<PlanningSessionDay> days, RoutePreferences prefs,
                                                CoverageBuildInfo coverageInfo, double totalKmFromBrouter) {
        double totalKm = days.stream()
                .mapToDouble(d -> d.distanceKm() != null ? d.distanceKm() : 0)
                .sum();
        if (totalKm <= 0) totalKm = totalKmFromBrouter; // fallback gdy podział dni nie wpisał distance
        int totalElev = days.stream()
                .mapToInt(d -> d.elevationGain() != null ? d.elevationGain() : 0)
                .sum();
        int budget = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : 0;
        // CLIMB-AWARE verdict: km overshoot usprawiedliwiony jesli climb undershoot kompensuje
        // (formula kara-nagroda: 1m climb mniej = 0.0667 km extra dla szosy).
        BudgetReconciler.Result r = BudgetReconciler.evaluateWithClimb(
                totalKm, totalElev, prefs.days(), prefs.kmPerDay(), prefs.elevationPerDayM());
        PlanningSummary.BudgetVerdict v = PlanningSummary.BudgetVerdict.valueOf(r.verdict().name());

        int initialPool = coverageInfo != null ? coverageInfo.initialPoolSize() : 0;
        int finalPool   = coverageInfo != null ? coverageInfo.finalPoolSize()   : 0;
        Double baselineKm = coverageInfo != null ? coverageInfo.baselineKm() : null;
        Double roadAreas = coverageInfo != null ? coverageInfo.roadAreas() : null;

        // Climb warning: realny wznios > refClimbTotal × 1.10 (CLIMB_WARNING_RATIO).
        int daysCount = prefs.days() != null ? prefs.days() : 1;
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : DEFAULT_KM_PER_DAY;
        double refClimbPerDay = (prefs.elevationPerDayM() != null && prefs.elevationPerDayM() > 0)
                ? prefs.elevationPerDayM()
                : Math.max(300.0, kmPerDay * DaySplitter.EFFORT_BASE_CLIMB_PER_180 / 180.0);
        double refClimbTotal = refClimbPerDay * daysCount;
        boolean climbWarning = totalElev > refClimbTotal * CLIMB_WARNING_RATIO;

        return new PlanningSummary(totalKm, totalElev, budget, v, r.surplusKm(),
                finalPool, initialPool,
                baselineKm, roadAreas, climbWarning);
    }

    private ChunkedBrouterRouter chunkedRouter;
    private ChunkedBrouterRouter chunkedRouter() {
        if (chunkedRouter == null) {
            chunkedRouter = new ChunkedBrouterRouter(routeUseCase, this::checkCancel,
                    (id, tile) -> { java.util.Set<String> sink = missingTilesPerTask.get(id); if (sink != null) sink.add(tile); });
        }
        return chunkedRouter;
    }


    private String resolveProfile(RoutePreferences prefs) {
        if (prefs.profile() != null && !prefs.profile().isBlank()) {
            return prefs.profile();
        }
        return "trekking-gminy"; // fallback gdy user nie wybrał profilu (asystent zawsze planuje gminy)
    }

    private void setPhase(UUID taskId, String phase) {
        try {
            if (progressSink != null && taskId != null) {
                progressSink.updatePhase(taskId, phase);
            }
        } catch (RuntimeException e) {
            log.debug("updatePhase failed: {}", e.getMessage());
        }
    }

    private void checkCancel(UUID taskId) {
        if (progressSink != null && progressSink.isCancelRequested(taskId)) {
            throw new TaskCancellationException("Plan computation cancelled by user");
        }
    }
}
