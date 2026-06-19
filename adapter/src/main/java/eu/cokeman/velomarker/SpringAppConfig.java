package eu.cokeman.velomarker;

import eu.cokeman.velomarker.mapper.PlanningJpaMapper;
import eu.cokeman.velomarker.mapper.RouteDraftJpaMapper;
import eu.cokeman.velomarker.out.persistence.jpa.repository.RouteDraftJpaRepository;
import eu.cokeman.velomarker.out.persistence.jpa.repository.RouteDraftRepositoryImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import velomarker.port.in.CalculateElevationUseCase;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.in.RouteDraftUseCase;
import velomarker.port.out.BrouterControlClient;
import velomarker.port.out.BrouterRoutingClient;
import velomarker.port.out.DemTileRemoteSource;
import velomarker.port.out.DemTileStorage;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.ProfileStorage;
import velomarker.port.out.RouteDraftRepository;
import velomarker.port.out.SegmentRemoteSource;
import velomarker.port.out.SegmentStorage;
import velomarker.port.in.planning.PlanningSessionUseCase;
import velomarker.port.in.planning.SavePlanAsExpeditionUseCase;
import velomarker.port.in.planning.UpdatePlanningDayUseCase;
import velomarker.port.out.planning.PlanTaskProgressPublisher;
import velomarker.port.out.planning.PlanTaskRepository;
import velomarker.port.out.planning.PlanningSessionDayRepository;
import velomarker.port.out.planning.PlanningSessionRepository;
import velomarker.port.out.planning.VisitServiceClient;
import velomarker.service.BrouterStatusService;
import velomarker.service.CalculateElevationService;
import velomarker.service.CalculateRouteService;
import velomarker.service.DemTileManagementService;
import velomarker.service.ProfileManagementService;
import velomarker.service.RouteDraftManagementService;
import velomarker.service.SegmentManagementService;
import velomarker.service.planning.AlnsCoveragePlanner;
import velomarker.service.planning.ComputationRegistry;
import velomarker.service.planning.DaySplitter;
import velomarker.service.planning.PlanTaskService;
import velomarker.service.planning.PlanningOrchestrationService;
import velomarker.service.planning.PlanningSessionService;
import velomarker.service.planning.ProfileMapper;
import velomarker.service.planning.WaypointSelector;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "eu.cokeman.velomarker.out.persistence.jpa.repository")
@EntityScan(basePackages = "eu.cokeman.velomarker.out.persistence.jpa.entity")
public class SpringAppConfig {

    @Bean
    SegmentManagementService segmentManagementService(SegmentRemoteSource remoteSource,
                                                       SegmentStorage storage,
                                                       BrouterControlClient controlClient) {
        return new SegmentManagementService(remoteSource, storage, controlClient);
    }

    @Bean
    BrouterStatusService brouterStatusService(BrouterControlClient controlClient) {
        return new BrouterStatusService(controlClient);
    }

    @Bean
    ProfileManagementService profileManagementService(ProfileStorage storage) {
        return new ProfileManagementService(storage);
    }

    @Bean
    DemTileManagementService demTileManagementService(DemTileRemoteSource remoteSource,
                                                       DemTileStorage storage) {
        return new DemTileManagementService(remoteSource, storage);
    }

    @Bean
    RouteDraftRepository routeDraftRepository(RouteDraftJpaRepository jpa, RouteDraftJpaMapper mapper) {
        return new RouteDraftRepositoryImpl(jpa, mapper);
    }

    @Bean
    CalculateRouteUseCase calculateRouteUseCase(BrouterRoutingClient brouterClient, ElevationDataSource elevationDataSource) {
        return new CalculateRouteService(brouterClient, elevationDataSource);
    }

    @Bean
    CalculateElevationUseCase calculateElevationUseCase(ElevationDataSource elevationDataSource) {
        return new CalculateElevationService(elevationDataSource);
    }

    @Bean
    RouteDraftUseCase routeDraftUseCase(RouteDraftRepository repository) {
        return new RouteDraftManagementService(repository);
    }

    // ===================== Planning (asystent tras) =====================

    @Bean
    PlanningJpaMapper planningJpaMapper() {
        return new PlanningJpaMapper();
    }

    @Bean
    WaypointSelector waypointSelector() {
        return new WaypointSelector();
    }

    @Bean
    ProfileMapper planningProfileMapper() {
        return new ProfileMapper();
    }

    @Bean
    ComputationRegistry planningComputationRegistry() {
        return new ComputationRegistry();
    }

    @Bean
    DaySplitter daySplitter() {
        return new DaySplitter();
    }

    /** Parametry ALNS z `planning.alns.*` (application.yml). */
    @Bean
    AlnsCoveragePlanner.AlnsParameters alnsParameters(
            @org.springframework.beans.factory.annotation.Value("${planning.alns.iterations:200}") int iterations,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.destroy-ratio:0.20}") double destroyRatio,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.lambda-budget:1.0}") double lambdaBudget,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.lambda-loops:5.0}") double lambdaLoops,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.lambda-balance:0.5}") double lambdaBalance,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.initial-temperature-ratio:0.1}") double initT,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.cooling-rate:0.995}") double coolingRate,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.no-improve-stop:50}") int noImproveStop,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.proxy-skip-threshold:2.0}") double proxySkip,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.max-time-seconds:240}") int maxTimeSec,
            @org.springframework.beans.factory.annotation.Value("${planning.alns.lambda-climb:1.5}") double lambdaClimb) {
        return new AlnsCoveragePlanner.AlnsParameters(iterations, destroyRatio, lambdaBudget, lambdaLoops,
                lambdaBalance, initT, coolingRate, noImproveStop, proxySkip, maxTimeSec, lambdaClimb);
    }

    /** ALNS planner. Tworzony zawsze (lekki), uzywany tylko gdy planning.algorithm=alns. */
    @Bean
    AlnsCoveragePlanner alnsCoveragePlanner(AlnsCoveragePlanner.AlnsParameters params,
                                              ElevationDataSource elevation) {
        return new AlnsCoveragePlanner(params, elevation);
    }

    /** TSP cheapest insertion + spatial grid planner. Domyslny algorytm. */
    @Bean
    velomarker.service.planning.tsp.TspCoveragePlanner tspCoveragePlanner() {
        return new velomarker.service.planning.tsp.TspCoveragePlanner();
    }

    /** ALNS2 — Orienteering / Max Coverage Path Solver (iter 10). */
    @Bean
    velomarker.service.planning.alns2.Alns2Parameters alns2Parameters(
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.alpha-km-per-meter:0.1}") double alpha,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.r-near-km:5.0}") double rNear,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.beta:0.3}") double beta,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.t-start:10.0}") double tStart,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.cooling-rate:0.95}") double coolingRate,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.max-iters:200}") int maxIters,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.no-improve-stop:50}") int noImproveStop,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.sample-points-per-gmina:5}") int samplePts,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.reward-reference-dist-km:10.0}") double rewardRefDist,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.destroy-ratio:0.10}") double destroyRatio,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.max-time-seconds:300}") int maxTimeSec,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.corridor-factor:0.05}") double corridorFactor,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.gamma:0.2}") double gamma,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.delta:0.5}") double delta) {
        return new velomarker.service.planning.alns2.Alns2Parameters(
                alpha, rNear, beta, tStart, coolingRate, maxIters, noImproveStop,
                samplePts, rewardRefDist, destroyRatio, maxTimeSec, corridorFactor, gamma, delta);
    }

    @Bean
    velomarker.service.planning.alns2.AlnsCoveragePlanner2 alnsCoveragePlanner2(
            velomarker.service.planning.alns2.Alns2Parameters params,
            ElevationDataSource elevation,
            @org.springframework.beans.factory.annotation.Value("${planning.algorithm:tsp}") String algorithm,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.strip-km:15.0}") double stripKm,
            // ALNS2 prewarmEdges używa virtual threads z semaforek `brouterParallelism` — to LIMIT
            // jednocześnie wywoływanych BRouter calls. Powinien być === route.brouter.max-concurrent
            // (default 16 dla embedded), żeby semafor embedded'a był w pełni wykorzystywany.
            // Zostawiamy `route.calculate.max-concurrent` jako fallback dla legacy http mode.
            @org.springframework.beans.factory.annotation.Value("${route.brouter.max-concurrent:${route.calculate.max-concurrent:8}}") int maxConcurrent,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.seed-only:false}") boolean seedOnly,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.proxy-search:false}") boolean proxySearch,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.proxy-cell-deg:0.5}") double proxyCellDeg,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.proxy-recalibrate-every:25}") int proxyRecalibrateEvery,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.reconcile-swap:true}") boolean reconcileSwap,
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.debug-geojson:false}") boolean debugGeoJson,
            // A/B: stara pętla DEEP-BATCH po seedzie. Default OFF — zastąpiona przez seed→105% + tailPrune.
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.deep-batch:false}") boolean deepBatch,
            // WIGGLE (warianty pozycji wp w gminie) — default OFF: 3 runy danych dały ~540 pytań
            // do BRoutera za ~190 effortu za każdym razem (0.3/call).
            @org.springframework.beans.factory.annotation.Value("${planning.alns2.wiggle:false}") boolean wiggle,
            velomarker.port.out.planning.AreaCoverageIndexFactory coverageIndexFactory) {
        // alns3 = ten sam planner w trybie space-filling (HILBERT). alns2 = projection.
        boolean spaceFilling = "alns3".equalsIgnoreCase(algorithm);
        return new velomarker.service.planning.alns2.AlnsCoveragePlanner2(
                params, elevation, spaceFilling, stripKm, maxConcurrent, seedOnly,
                proxySearch, proxyCellDeg, proxyRecalibrateEvery, reconcileSwap, coverageIndexFactory, debugGeoJson,
                deepBatch, wiggle);
    }

    @Bean
    PlanningOrchestrationService planningOrchestrationService(
            PlanningSessionRepository sessionRepository,
            PlanningSessionDayRepository dayRepository,
            VisitServiceClient visitClient,
            CalculateRouteUseCase routeUseCase,
            BrouterRoutingClient brouterClient,
            velomarker.port.out.planning.AreaCoverageIndexFactory coverageIndexFactory,
            ElevationDataSource elevation,
            WaypointSelector waypointSelector,
            ProfileMapper profileMapper,
            DaySplitter daySplitter,
            AlnsCoveragePlanner alnsPlanner,
            velomarker.service.planning.tsp.TspCoveragePlanner tspPlanner,
            velomarker.service.planning.alns2.AlnsCoveragePlanner2 alns2Planner,
            @org.springframework.beans.factory.annotation.Value("${planning.algorithm:tsp}") String algorithm) {
        return new PlanningOrchestrationService(sessionRepository, dayRepository, visitClient, routeUseCase,
                brouterClient, coverageIndexFactory, elevation, waypointSelector, profileMapper, daySplitter, algorithm, alnsPlanner, tspPlanner, alns2Planner);
    }

    /** Virtual-thread executor — każde liczenie planu w osobnym wątku wirtualnym (tanio). */
    @Bean
    ExecutorService planningTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Domyślny no-op publisher — zastąpiony przez AMQP adapter (gdy będzie zaimplementowany). */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PlanTaskProgressPublisher.class)
    PlanTaskProgressPublisher noopPlanTaskProgressPublisher() {
        return task -> {
            // best-effort no-op (dev fallback)
        };
    }

    @Bean
    PlanTaskService planTaskService(PlanTaskRepository taskRepository,
                                    PlanningSessionRepository sessionRepository,
                                    PlanningOrchestrationService orchestration,
                                    ComputationRegistry computationRegistry,
                                    PlanTaskProgressPublisher publisher,
                                    ExecutorService planningTaskExecutor) {
        PlanTaskService svc = new PlanTaskService(taskRepository, sessionRepository, orchestration,
                computationRegistry, publisher, planningTaskExecutor);
        // setter injection — rozwiązuje cykl OrchestrationService ↔ PlanTaskService
        orchestration.setProgressSink(svc);
        return svc;
    }

    @Bean
    PlanningSessionService planningSessionService(PlanningSessionRepository sessionRepository,
                                                  PlanningSessionDayRepository dayRepository,
                                                  CalculateRouteUseCase routeUseCase,
                                                  ElevationDataSource elevation,
                                                  RouteDraftUseCase routeDraftUseCase) {
        return new PlanningSessionService(sessionRepository, dayRepository, routeUseCase, elevation, routeDraftUseCase);
    }
}
