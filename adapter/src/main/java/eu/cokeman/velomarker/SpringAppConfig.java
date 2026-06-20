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
import velomarker.service.CalculateElevationService;
import velomarker.service.CalculateRouteService;
import velomarker.service.DemTileManagementService;
import velomarker.service.ProfileManagementService;
import velomarker.service.RouteDraftManagementService;
import velomarker.service.SegmentManagementService;
import velomarker.service.planning.ComputationRegistry;
import velomarker.service.planning.DaySplitter;
import velomarker.service.planning.PlanTaskService;
import velomarker.service.planning.PlanningOrchestrationService;
import velomarker.service.planning.PlanningSessionService;
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
                                                       SegmentStorage storage) {
        return new SegmentManagementService(remoteSource, storage);
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
    ComputationRegistry planningComputationRegistry() {
        return new ComputationRegistry();
    }

    @Bean
    DaySplitter daySplitter() {
        return new DaySplitter();
    }

    /** Parametry plannera pokrycia z `planning.coverage.*` (application.yml). */
    @Bean
    velomarker.service.planning.coverage.CoveragePlannerParameters coveragePlannerParameters(
            @org.springframework.beans.factory.annotation.Value("${planning.coverage.alpha-km-per-meter:0.1}") double alpha) {
        return new velomarker.service.planning.coverage.CoveragePlannerParameters(alpha);
    }

    @Bean
    velomarker.service.planning.coverage.CoveragePlanner coveragePlanner(
            velomarker.service.planning.coverage.CoveragePlannerParameters params,
            ElevationDataSource elevation,
            // Coverage prewarmEdges używa virtual threads z semaforek `brouterParallelism` — to LIMIT
            // jednocześnie wywoływanych BRouter calls. Powinien być === route.brouter.max-concurrent
            // (default 16 dla embedded), żeby semafor embedded'a był w pełni wykorzystywany.
            // Zostawiamy `route.calculate.max-concurrent` jako fallback dla legacy http mode.
            @org.springframework.beans.factory.annotation.Value("${route.brouter.max-concurrent:${route.calculate.max-concurrent:8}}") int maxConcurrent,
            @org.springframework.beans.factory.annotation.Value("${planning.coverage.debug-geojson:false}") boolean debugGeoJson,
            velomarker.port.out.planning.AreaCoverageIndexFactory coverageIndexFactory,
            velomarker.port.out.planning.SpatialIndexFactory spatialIndexFactory) {
        return new velomarker.service.planning.coverage.CoveragePlanner(
                params, elevation, maxConcurrent,
                coverageIndexFactory, spatialIndexFactory, debugGeoJson);
    }

    @Bean
    PlanningOrchestrationService planningOrchestrationService(
            PlanningSessionRepository sessionRepository,
            PlanningSessionDayRepository dayRepository,
            VisitServiceClient visitClient,
            CalculateRouteUseCase routeUseCase,
            BrouterRoutingClient brouterClient,
            velomarker.port.out.planning.AreaCoverageIndexFactory coverageIndexFactory,
            velomarker.port.out.planning.SpatialIndexFactory spatialIndexFactory,
            ElevationDataSource elevation,
            WaypointSelector waypointSelector,
            DaySplitter daySplitter,
            velomarker.service.planning.coverage.CoveragePlanner coveragePlanner) {
        return new PlanningOrchestrationService(sessionRepository, dayRepository, visitClient, routeUseCase,
                brouterClient, coverageIndexFactory, spatialIndexFactory, elevation, waypointSelector, daySplitter, coveragePlanner);
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
