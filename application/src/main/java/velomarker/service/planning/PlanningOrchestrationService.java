package velomarker.service.planning;

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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Główny mózg asystenta: sesja + preferences + intent → lista dni planu.
public class PlanningOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PlanningOrchestrationService.class);


    static final int DEFAULT_KM_PER_DAY = 100;

    static final double ANCHOR_REACHABILITY_KM = 2.0;
    static final int DAY_SPLIT_ELEVATION_SAMPLES = 2000;
    static final int DAY_SPLIT_ELEVATION_SAMPLES_CAP = 200000;

    private final PlanningSessionRepository sessionRepository;
    private final PlanningSessionDayRepository dayRepository;
    private final VisitServiceClient visitClient;
    private final CalculateRouteUseCase routeUseCase;
    private final BrouterRoutingClient brouterClient;
    private final AreaCoverageIndexFactory coverageIndexFactory;
    private final SpatialIndexFactory spatialIndexFactory;
    private final ElevationDataSource elevation;
    private final WaypointSelector waypointSelector;
    private final velomarker.service.planning.coverage.CoveragePlanner coveragePlanner;
    private final velomarker.port.out.planning.PlanTracePublisher tracePublisher;
    private PlanProgressSink progressSink;
    private final ThreadLocal<Span> phaseSpan = new ThreadLocal<>();
    private final ThreadLocal<Scope> phaseScope = new ThreadLocal<>();

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
                                        velomarker.service.planning.coverage.CoveragePlanner coveragePlanner,
                                        velomarker.port.out.planning.PlanTracePublisher tracePublisher) {
        this.tracePublisher = tracePublisher;
        this.sessionRepository = sessionRepository;
        this.dayRepository = dayRepository;
        this.visitClient = visitClient;
        this.routeUseCase = routeUseCase;
        this.brouterClient = brouterClient;
        this.coverageIndexFactory = coverageIndexFactory;
        this.spatialIndexFactory = spatialIndexFactory;
        this.elevation = elevation;
        this.waypointSelector = waypointSelector;
        this.coveragePlanner = coveragePlanner;
        log.info("PlanningOrchestrationService initialized (coverage planner {})",
                coveragePlanner != null ? "present" : "absent");
    }

    public void setProgressSink(PlanProgressSink sink) {
        this.progressSink = sink;
    }

    public void executePlan(UUID taskId, UUID userId, String bearerToken) {
        missingTilesPerTask.put(taskId, java.util.concurrent.ConcurrentHashMap.newKeySet());
        long planStartTs = System.currentTimeMillis();
        PlanTimings timings = new PlanTimings();
        try {
            PlanningSession session = loadValidatedSession(userId);
            RoutePreferences prefs = session.preferences();

            setPhase(taskId, "validating");
            checkCancel(taskId);

            String profile = resolveProfile(prefs);
            resetPlanCounters();
            logPlanStart(taskId, session, profile, prefs);

            // Nowe liczenie ZAWSZE czyści poprzedni wynik — stare dni znikają od razu i po ew. błędzie
            // (np. „wszystkie obszary odwiedzone") nie wracają przez loadSession na froncie.
            dayRepository.replaceAll(session.id(), List.of());

            WaypointBuild wb = buildWaypointsForIntent(taskId, session, prefs, profile, bearerToken, timings);
            RouteCalculation full = routePlan(taskId, prefs, wb, profile);
            List<Waypoint> allWaypoints = wb.allWaypoints();

            setPhase(taskId, "sampling-elevation");
            checkCancel(taskId);
            ElevationProfile fullProfile = sampleElevationForSplit(prefs, full);

            setPhase(taskId, "splitting-days");
            checkCancel(taskId);
            List<DayBoundary> boundaries = new DaySplitter().splitIntoDays(fullProfile, prefs.days());

            List<PlanningSessionDay> days = new DayBuilder(elevation, spatialIndexFactory, this::checkCancel, this::setPhase).build(taskId, session, profile, full, fullProfile, boundaries, allWaypoints);

            checkCancel(taskId);
            setPhase(taskId, "saving");
            saveAndSummarize(taskId, userId, session, days, prefs, full);
            logPlanTimings(taskId, timings, System.currentTimeMillis() - planStartTs);
        } finally {
            closePhaseSpan();
            reportMissingTiles(taskId);
        }
    }

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

    private void resetPlanCounters() {
        brouterClient.resetPlanCounters();
        routeUseCase.resetPlanCounters();
    }

    private void logPlanStart(UUID taskId, PlanningSession session, String profile, RoutePreferences prefs) {
        Waypoint startWp = prefs.start();
        log.info("PLAN START: task={} intent={} profile={} days={} kmPerDay={} elevPerDay={} start={} via={} loop={}",
                new Object[]{taskId, session.intent(), profile, prefs.days(), prefs.kmPerDay(),
                        prefs.elevationPerDayM(),
                        startWp == null ? "null" : String.format(java.util.Locale.ROOT, "%.5f,%.5f", startWp.lng(), startWp.lat()),
                        prefs.via() == null ? 0 : prefs.via().size(), prefs.loop()});
    }

    /** Pełny czas planowania (od wejścia executePlan) + rozbicie na fazy. „pozostałe" = total − baseline − dobieranie − finalizacja. */
    private void logPlanTimings(UUID taskId, PlanTimings t, long totalMs) {
        long restMs = Math.max(0, totalMs - t.baselineMs() - t.pickingMs() - t.finalizeMs());
        log.info("PLAN TIMING task={} total={}s | baseline={}s | dobieranie={}s | finalizacja={}s | pozostałe={}s",
                new Object[]{taskId, totalMs / 1000, t.baselineMs() / 1000, t.pickingMs() / 1000,
                        t.finalizeMs() / 1000, restMs / 1000});
    }

    private RouteCalculation routePlan(UUID taskId, RoutePreferences prefs, WaypointBuild wb, String profile) {
        setPhase(taskId, "routing-brouter");
        checkCancel(taskId);
        var coverageResult = wb.coverageResult();
        if (coverageResult != null) {
            RouteCalculation full = coverageResult.calc();
            log.info("Coverage planner bypass: actualKm={} visited={} brouterCalls={}",
                    new Object[]{Math.round(full.distanceKm()), coverageResult.visited().size(),
                            coverageResult.brouterCalls()});
            return full;
        }
        return new RouteTracer(chunkedRouter(), this::checkCancel, taskId, prefs, wb.allWaypoints(), profile, ANCHOR_REACHABILITY_KM).trace();
    }

    private ElevationProfile sampleElevationForSplit(RoutePreferences prefs, RouteCalculation full) {
        int splitSamples = Math.min(DAY_SPLIT_ELEVATION_SAMPLES_CAP,
                Math.max(DAY_SPLIT_ELEVATION_SAMPLES,
                        Math.max(prefs.days() != null ? prefs.days() * 3 : 0, full.coordinates().size())));
        return elevation.sample(full.coordinates(), splitSamples);
    }

    private void saveAndSummarize(UUID taskId, UUID userId, PlanningSession session, List<PlanningSessionDay> days,
                                  RoutePreferences prefs, RouteCalculation full) {
        dayRepository.replaceAll(session.id(), days);
        session.setSummary(buildSummary(days, prefs, full.distanceKm()));
        sessionRepository.save(session);
        logPlanSummary(userId, taskId, session.summary());
        logDayDistribution(days);
    }

    private void logPlanSummary(UUID userId, UUID taskId, PlanningSummary s) {
        log.info("Plan summary user={} task={} budgetFit={}", new Object[]{userId, taskId, s.budgetFit()});
    }

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

    private WaypointBuild buildWaypointsForIntent(UUID taskId, PlanningSession session, RoutePreferences prefs,
                                                  String profile, String bearerToken, PlanTimings timings) {
            CoverageBuildInfo coverageInfo = null;
            List<Waypoint> allWaypoints;
            CoverageResult coverageResult = null;
            switch (session.intent()) {
                case AB -> allWaypoints = buildAbWaypoints(prefs);
                case FREESTYLE -> allWaypoints = buildFreestyleWaypoints(prefs);
                case COVERAGE -> {
                    coverageInfo = new CoverageWaypointBuilder(visitClient, waypointSelector, this::checkCancel, this::setPhase, coverageIndexFactory, routeUseCase).build(taskId, prefs, bearerToken, profile, timings);
                    allWaypoints = coverageInfo.waypoints();
                    // Live-podgląd planowania (SSE) — sink związany z tym taskiem; każdy checkpoint geometrii leci na front.
                    velomarker.service.planning.PlanTraceSink traceSink =
                            frame -> tracePublisher.publish(taskId, session.userId(), frame);
                    traceSink.emit(new velomarker.service.planning.PlanTraceFrame(
                            "baseline", coverageInfo.baselineGeometry(), 0, 0, java.util.List.of(), java.util.Map.of()));
                    if (coveragePlanner != null) {
                        setPhase(taskId, "coverage-planning");
                        checkCancel(taskId);
                        BrouterFn brouter =
                                (wps, prof, stats) -> chunkedRouter().route(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, stats);
                        List<UnvisitedArea> coveragePool = new ArrayList<>();
                        if (coverageInfo.pickedCandidates() != null) {
                            for (AreaCandidate c : coverageInfo.pickedCandidates()) coveragePool.add(c.area);
                        }
                        log.info("Coverage planner: pool={} areas + historycznie zaliczone={} (sąsiedztwo)",
                                new Object[]{coveragePool.size(), coverageInfo.historicallyVisited().size()});
                        java.util.Map<Integer, String> areaRodzajName = buildAreaRodzajNames(coveragePool, bearerToken);
                        coverageResult = coveragePlanner.plan(coveragePool, coverageInfo.historicallyVisited(),
                                coverageInfo.baselineGeometry(), prefs, profile,
                                brouter, brouterClient::setSnapLogging, areaRodzajName, traceSink, timings);
                        if (coverageResult != null) {
                            log.info("Coverage planner: visited={} brouterCalls={}",
                                    new Object[]{coverageResult.visited().size(), coverageResult.brouterCalls()});
                            allWaypoints = coverageResult.finalWaypoints();
                        } else {
                            log.warn("Coverage planner returned null -- fallback do greedy");
                        }
                    }
                }
                case TILES -> {
                    velomarker.service.planning.PlanTraceSink traceSink =
                            frame -> tracePublisher.publish(taskId, session.userId(), frame);
                    BrouterFn brouter =
                            (wps, prof, stats) -> chunkedRouter().route(taskId,
                                    wps.stream().map(Waypoint::toLngLat).toList(), prof, stats);
                    coverageResult = new TileWaypointBuilder(coveragePlanner, this::checkCancel, this::setPhase)
                            .build(taskId, prefs, profile, brouter, brouterClient::setSnapLogging, traceSink, timings);
                    if (coverageResult != null) {
                        log.info("TILES planner: visited(tiles)={} brouterCalls={}",
                                new Object[]{coverageResult.visited().size(), coverageResult.brouterCalls()});
                        allWaypoints = coverageResult.finalWaypoints();
                    } else {
                        log.warn("TILES planner returned null -- fallback do anchor-only (start/via/meta)");
                        allWaypoints = buildFreestyleWaypoints(prefs);
                    }
                }
                default -> throw new IllegalStateException("Unknown intent: " + session.intent());
            }
        return new WaypointBuild(allWaypoints, coverageInfo, coverageResult);
    }

    /**
     * Mapa {@code areaId → nazwa rodzaju} (np. „Powiat"/„Bezirk"/nazwa grupy specjalnej) dla live-podglądu.
     * Katalog poziomów i grup z visit-service (cache 1h). Best-effort: gdy katalog padnie, fallback
     * „Poziom {levelId}" — trasa się liczy mimo to. (Pełna niedostępność visit-service już wcześniej
     * przerwała planowanie na pobraniu puli obszarów.)
     */
    private java.util.Map<Integer, String> buildAreaRodzajNames(List<UnvisitedArea> pool, String bearerToken) {
        java.util.Map<Integer, String> levelNames;
        try {
            levelNames = visitClient.listLevelNames(bearerToken);
        } catch (RuntimeException e) {
            log.warn("Katalog poziomów (countries/with-levels) niedostępny — fallback do Poziom N: {}", e.getMessage());
            levelNames = java.util.Map.of();
        }
        java.util.Map<Integer, String> sgNames = new java.util.HashMap<>();
        try {
            for (velomarker.port.out.planning.SpecialGroupRef ref : visitClient.listSpecialGroupsCatalog(bearerToken)) {
                sgNames.putIfAbsent(ref.groupId(), ref.name());
            }
        } catch (RuntimeException e) {
            log.warn("Katalog grup specjalnych niedostępny — fallback do levelName: {}", e.getMessage());
        }
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (UnvisitedArea a : pool) {
            String rodzaj;
            if (a.specialGroupId() != null) {
                rodzaj = sgNames.getOrDefault(a.specialGroupId(), a.levelName());
            } else {
                rodzaj = levelNames.getOrDefault(a.levelId(), "Poziom " + a.levelId());
            }
            out.put(a.areaId(), rodzaj);
        }
        return out;
    }

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

    private static PlanningSummary buildSummary(List<PlanningSessionDay> days, RoutePreferences prefs,
                                                double totalKmFromBrouter) {
        double totalKm = days.stream().mapToDouble(d -> d.distanceKm() != null ? d.distanceKm() : 0).sum();
        if (totalKm <= 0) totalKm = totalKmFromBrouter;
        int totalElev = days.stream().mapToInt(d -> d.elevationGain() != null ? d.elevationGain() : 0).sum();

        int daysCount = prefs.days() != null ? prefs.days() : 1;
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : DEFAULT_KM_PER_DAY;
        double elevPerDay = (prefs.elevationPerDayM() != null && prefs.elevationPerDayM() > 0)
                ? prefs.elevationPerDayM()
                : Math.max(300.0, kmPerDay * DaySplitter.EFFORT_BASE_CLIMB_PER_180 / 180.0);

        double tripEffort = totalKm + 0.1 * totalElev;
        double budgetEffort = daysCount * (kmPerDay + 0.1 * elevPerDay);
        double frac = budgetEffort > 0 ? tripEffort / budgetEffort : 1.0;
        PlanningSummary.BudgetFit fit = frac > 1.05 ? PlanningSummary.BudgetFit.OVER
                : frac < 0.95 ? PlanningSummary.BudgetFit.UNDER
                : PlanningSummary.BudgetFit.OK;
        return new PlanningSummary(fit);
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
        return "bike";
    }

    private void setPhase(UUID taskId, String phase) {
        try {
            if (progressSink != null && taskId != null) {
                progressSink.updatePhase(taskId, phase);
            }
        } catch (RuntimeException e) {
            log.debug("updatePhase failed: {}", e.getMessage());
        }
        closePhaseSpan();
        Span sp = GlobalOpenTelemetry.get().getTracer("route-service-plan").spanBuilder("plan." + phase).startSpan();
        phaseSpan.set(sp);
        phaseScope.set(sp.makeCurrent());
    }

    private void closePhaseSpan() {
        Scope sc = phaseScope.get();
        if (sc != null) { sc.close(); phaseScope.remove(); }
        Span sp = phaseSpan.get();
        if (sp != null) { sp.end(); phaseSpan.remove(); }
    }

    private void checkCancel(UUID taskId) {
        if (progressSink != null && progressSink.isCancelRequested(taskId)) {
            throw new TaskCancellationException("Plan computation cancelled by user");
        }
    }
}
