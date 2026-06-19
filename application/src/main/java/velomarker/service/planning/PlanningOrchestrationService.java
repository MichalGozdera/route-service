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
import velomarker.entity.planning.RouteStyle;
import velomarker.entity.planning.Tempo;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.exception.PlanningSessionMissingException;
import velomarker.exception.PlanningSessionNotReadyException;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.out.BrouterRoutingClient;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.AreaCoverage;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.AreaCoverageIndexFactory;
import velomarker.port.out.planning.PlanningSessionDayRepository;
import velomarker.port.out.planning.PlanningSessionRepository;
import velomarker.port.out.planning.VisitServiceClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 *
 * <p>MVP scope: AB i FREESTYLE w pełni; COVERAGE z prostym TSP (bez bisekcji budżetu).
 */
public class PlanningOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PlanningOrchestrationService.class);

    static final int MAX_AREAS_TO_FETCH = 40000;
    /** Cap puli PO sumowaniu (kraj × poziom × grupy specjalne). 2-opt to O(n²×passes), powyżej tego TSP się wykrwawia. */
    static final int MAX_TSP_AREAS = 500;
    static final int MAX_WAYPOINTS_PER_DAY = 48;
    static final int DEFAULT_KM_PER_DAY = 100;
    /** Max chunków BRoutera liczonych RÓWNOLEGLE (finalny chunked call). ≤ route.calculate.max-concurrent
     * (=10) by nie przepełnić semafora klienta (5s wait → 429). Cała Polska = 42 chunki → fale po 6. */
    static final int MAX_PARALLEL_CHUNKS = 6;

    /** Budget reconcile constants. */
    static final int MAX_BUDGET_TRIM = 6;
    static final int MAX_BUDGET_GROW = 4;
    static final double BUDGET_TOLERANCE = 0.05;   // ±5% wokół budżetu = OK
    /** Max gap (km) między dowolnym anchor'em a najbliższym punktem geometrii BRouter. Powyżej → FAIL. */
    static final double ANCHOR_REACHABILITY_KM = 2.0;
    /** Max obszarów wziętych do density probe (jeden BRouter call → roadAreas). */
    static final int DENSITY_PROBE_AREAS = 30;
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
    private final ElevationDataSource elevation;
    private final WaypointSelector waypointSelector;
    private final ProfileMapper profileMapper;
    private final DaySplitter daySplitter;
    /** Feature flag: greedy (obecny TRIM/GROW/DEDUP) vs alns (Adaptive Large Neighborhood Search). */
    private final String algorithm;
    /** ALNS post-processor (opcjonalny, wstrzykiwany gdy planning.algorithm=alns). */
    private final AlnsCoveragePlanner alnsPlanner;
    /** TSP cheapest insertion planner (opcjonalny, wstrzykiwany gdy planning.algorithm=tsp). */
    private final velomarker.service.planning.tsp.TspCoveragePlanner tspPlanner;
    /** ALNS2 Orienteering/MCP planner (iter 10, planning.algorithm=alns2). */
    private final velomarker.service.planning.alns2.AlnsCoveragePlanner2 alns2Planner;
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
                                        ElevationDataSource elevation,
                                        WaypointSelector waypointSelector,
                                        ProfileMapper profileMapper,
                                        DaySplitter daySplitter,
                                        String algorithm,
                                        AlnsCoveragePlanner alnsPlanner,
                                        velomarker.service.planning.tsp.TspCoveragePlanner tspPlanner,
                                        velomarker.service.planning.alns2.AlnsCoveragePlanner2 alns2Planner) {
        this.sessionRepository = sessionRepository;
        this.dayRepository = dayRepository;
        this.visitClient = visitClient;
        this.routeUseCase = routeUseCase;
        this.brouterClient = brouterClient;
        this.coverageIndexFactory = coverageIndexFactory;
        this.elevation = elevation;
        this.waypointSelector = waypointSelector;
        this.profileMapper = profileMapper;
        this.daySplitter = daySplitter;
        this.algorithm = algorithm != null ? algorithm.toLowerCase() : "greedy";
        this.alnsPlanner = alnsPlanner;
        this.tspPlanner = tspPlanner;
        this.alns2Planner = alns2Planner;
        log.info("PlanningOrchestrationService initialized with algorithm={} (alns={}, tsp={}, alns2={})",
                new Object[]{this.algorithm,
                        alnsPlanner != null ? "present" : "absent",
                        tspPlanner != null ? "present" : "absent",
                        alns2Planner != null ? "present" : "absent"});
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
            PlanningSession session = sessionRepository.findByUserId(userId)
                    .orElseThrow(() -> new PlanningSessionMissingException(userId));
            if (session.intent() == null) {
                throw new PlanningSessionMissingException("Intent not set");
            }
            RoutePreferences prefs = session.preferences();
            if (!prefs.isReadyToCalculate(session.intent())) {
                throw new PlanningSessionNotReadyException("required fields missing for intent " + session.intent());
            }

            setPhase(taskId, "validating");
            checkCancel(taskId);

            String profile = resolveProfile(prefs);
            // Parametry wejściowe planu — jedna linia na starcie (diagnostyka: z czym wystartował dany run).
            Waypoint startWp = prefs.start();
            brouterClient.resetPlanCounters(); // v3.16: licznik „BRouter cumulative" liczy TEN plan, nie od startu serwisu
            routeUseCase.resetPlanCounters();  // v3.17: licznik „Elevation enrichment timing" też per plan
            log.info("PLAN START: task={} intent={} profile={} days={} kmPerDay={} elevPerDay={} start={} via={} loop={} (style={}, tempo={})",
                    new Object[]{taskId, session.intent(), profile, prefs.days(), prefs.kmPerDay(),
                            prefs.elevationPerDayM(),
                            startWp == null ? "null" : String.format(java.util.Locale.ROOT, "%.5f,%.5f", startWp.lng(), startWp.lat()),
                            prefs.via() == null ? 0 : prefs.via().size(), prefs.loop(),
                            prefs.style(), prefs.tempo()});
            RoadFactorCalibrator calibrator = new RoadFactorCalibrator();

            CoverageBuildInfo coverageInfo = null;
            List<Waypoint> allWaypoints;
            // ALNS / TSP BRANCH: gdy algorithm=alns | tsp + COVERAGE -> robi pełen routing
            // (z BRouter incremental) i ZWRACA gotowy RouteCalculation. Pomijamy
            // routeWithTrimAndDedup poniej. Greedy result jest CIEPŁYM STARTEM.
            AlnsCoveragePlanner.AlnsResult alnsResult = null;
            velomarker.service.planning.tsp.TspCoveragePlanner.TspResult tspResult = null;
            velomarker.service.planning.alns2.AlnsCoveragePlanner2.Alns2Result alns2Result = null;
            switch (session.intent()) {
                case AB -> allWaypoints = buildAbWaypoints(prefs);
                case FREESTYLE -> allWaypoints = buildFreestyleWaypoints(prefs);
                case COVERAGE -> {
                    coverageInfo = buildCoverageWaypointsWithInfo(taskId, prefs, bearerToken, calibrator);
                    allWaypoints = coverageInfo.waypoints;
                    if ("tsp".equals(algorithm) && tspPlanner != null) {
                        setPhase(taskId, "tsp-cheapest-insertion");
                        checkCancel(taskId);
                        java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouterFn =
                                (wps, prof) -> calculateRouteChunked(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, false);
                        // brouterFinal — TYLKO dla finalnego recompute przed return z plannera.
                        // computeStats=true → zwracany RouteCalculation ma pelne stats (surface/road/
                        // smoothness + spans) potrzebne dla per-day slicing w orchestratorze.
                        java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal =
                                (wps, prof) -> calculateRouteChunked(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, true);
                        // Climb-aware GROW: TspCoveragePlanner moze rozszerzyc km budget jesli wznios
                        // nie jest wykorzystany. Callback zwraca gainM dla geometrii.
                        java.util.function.Function<List<double[]>, Integer> elevationGainFn =
                                geom -> elevation.sample(geom).gainM();
                        tspResult = tspPlanner.plan(taskId, coverageInfo, prefs, profile,
                                calibrator, brouterFn, brouterFinal, this::checkCancel, elevationGainFn);
                        if (tspResult != null) {
                            log.info("TSP done: picked={} waypoints={} realKm={}",
                                    new Object[]{tspResult.picked().size(), tspResult.finalWaypoints().size(),
                                            Math.round(tspResult.calc().distanceKm())});
                            allWaypoints = tspResult.finalWaypoints();
                            // Iter 9: pipeline TSP→ALNS USUNIĘTY. `algorithm=tsp` = czyste TSP,
                            // `algorithm=alns` = czyste ALNS. Kombinacje nie pomagały z nawrotkami.
                        } else {
                            log.warn("TSP returned null -- fallback do greedy + routeWithTrimAndDedup");
                        }
                    } else if ("alns".equals(algorithm) && alnsPlanner != null) {
                        setPhase(taskId, "alns-optimizing");
                        checkCancel(taskId);
                        // Wstrzykujemy BRouter callable (binds taskId + profile)
                        final String alnsProfile = profile;
                        java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouterFn =
                                (wps, prof) -> calculateRouteChunked(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, false);
                        java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal =
                                (wps, prof) -> calculateRouteChunked(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, true);
                        alnsResult = alnsPlanner.optimize(taskId, coverageInfo, prefs, profile,
                                calibrator, brouterFn, brouterFinal, this::checkCancel);
                        if (alnsResult != null) {
                            log.info("ALNS branch: bypassing routeWithTrimAndDedup, using ALNS result directly");
                            allWaypoints = alnsResult.finalWaypoints();
                        } else {
                            log.warn("ALNS returned null -- fallback do greedy + routeWithTrimAndDedup");
                        }
                    } else if ("alns".equals(algorithm) && alnsPlanner == null) {
                        log.warn("planning.algorithm=alns ale AlnsCoveragePlanner bean nie wstrzykniety -- fallback do greedy");
                    } else if (("alns2".equals(algorithm) || "alns3".equals(algorithm)) && alns2Planner != null) {
                        // ALNS2 (projection) / ALNS3 (serpentine space-filling) — ten sam planner,
                        // tryb wybrany w SpringAppConfig wg planning.algorithm.
                        setPhase(taskId, "alns2-orienteering");
                        checkCancel(taskId);
                        java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouterFn =
                                (wps, prof) -> calculateRouteChunked(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, false);
                        java.util.function.BiFunction<List<Waypoint>, String, RouteCalculation> brouterFinal =
                                (wps, prof) -> calculateRouteChunked(taskId,
                                        wps.stream().map(Waypoint::toLngLat).toList(), prof, true);
                        // Pool = wszystkie areas z bbox-filtered coverageInfo (picked + reserve)
                        List<UnvisitedArea> alns2Pool = new ArrayList<>();
                        if (coverageInfo.pickedCandidates() != null) {
                            for (AreaCandidate c : coverageInfo.pickedCandidates()) alns2Pool.add(c.area);
                        }
                        if (coverageInfo.reserveCandidates() != null) {
                            for (AreaCandidate c : coverageInfo.reserveCandidates()) alns2Pool.add(c.area);
                        }
                        log.info("ALNS2: pool={} areas (z greedy bbox-filtered)", alns2Pool.size());
                        alns2Result = alns2Planner.plan(taskId, alns2Pool, coverageInfo.baselineGeometry(), prefs, profile,
                                brouterFn, brouterFinal, this::checkCancel);
                        if (alns2Result != null) {
                            log.info("ALNS2 branch: visited={} iters={} brouterCalls={}",
                                    new Object[]{alns2Result.visited().size(),
                                            alns2Result.iterations(), alns2Result.brouterCalls()});
                            allWaypoints = alns2Result.finalWaypoints();
                        } else {
                            log.warn("ALNS2 returned null -- fallback do greedy");
                        }
                    }
                }
                default -> throw new IllegalStateException("Unknown intent: " + session.intent());
            }

            // COVERAGE już zrobił probe (baseline + density), AB/FREESTYLE robią dopiero teraz.
            if (session.intent() != PlanningIntent.COVERAGE) {
                setPhase(taskId, "calibrating-road-factor");
                checkCancel(taskId);
                calibrateRoadFactor(allWaypoints, profile, calibrator);
            }

            setPhase(taskId, "routing-brouter");
            checkCancel(taskId);
            RouteCalculation full;
            if (tspResult != null) {
                full = tspResult.calc();
                allWaypoints = tspResult.finalWaypoints();
                log.info("TSP bypass: actualKm={} picked={} brouterCalls={} inserts={}",
                        new Object[]{Math.round(full.distanceKm()), tspResult.picked().size(),
                                tspResult.brouterCalls(), tspResult.inserts()});
            } else if (alnsResult != null) {
                // ALNS BRANCH: pomin TRIM/GROW/DEDUP -- ALNS dal nam GOTOWY route.
                full = alnsResult.calc();
                allWaypoints = alnsResult.finalWaypoints();
                log.info("ALNS bypass: actualKm={} picked={} brouterCalls={} accepted={}",
                        new Object[]{Math.round(full.distanceKm()), alnsResult.picked().size(),
                                alnsResult.brouterCalls(), alnsResult.accepted()});
            } else if (alns2Result != null) {
                // ALNS2 BRANCH (Iter 10): Orienteering/MCP wynik
                full = alns2Result.calc();
                allWaypoints = alns2Result.finalWaypoints();
                log.info("ALNS2 bypass: actualKm={} visited={} brouterCalls={} accepted={}",
                        new Object[]{Math.round(full.distanceKm()), alns2Result.visited().size(),
                                alns2Result.brouterCalls(), alns2Result.accepted()});
            } else {
                // GREEDY BRANCH: BRouter chunked + trim loop (max 8 prób)
                TraceResult traced = routeWithTrimAndDedup(taskId, prefs, allWaypoints, coverageInfo, profile,
                        calibrator, ANCHOR_REACHABILITY_KM);
                full = traced.calc;
                allWaypoints = traced.finalWaypoints;
            }
            // Stats per-day slicing dziala czysto: TSP/ALNS/ALNS2 same robia final recompute z brouterFinal
            // przed return (zob. brouterFinal w lambdas powyzej), wiec `full.stats()` ma spans z pelnej trasy.

            setPhase(taskId, "sampling-elevation");
            checkCancel(taskId);
            // DaySplitter potrzebuje WYZSZEJ rozdzielczosci (2000) niz domyslne 500 dla UI.
            // 500 sample na 1700 km = 3.4 km/sample -- znika cala gorka miedzy dwoma sample.
            // Per-day climb potem liczony jest osobno z dayElev (~0.4 km/sample), wiec dni
            // nieproporcjonalnie roznily sie effort'em. 2000 sample = 0.85 km/sample = ~9x
            // dokladniej -> effort split max/min ~1.15x zamiast 1.38x.
            // PEŁNA GRANULACJA: zamiast 2000 evenly-spaced sampli (gubi małe wzgórki → splitter myśli
            // że dni są równe, a wizualnie km/climb wychodzi 20% spread), bierzemy MIN(coords, CAP).
            // User: „po ostatnim reconcile weź całą trasę i pociachaj" — splitter widzi każdy coord
            // BRoutera z elevation. Cap 30000 dla mega-tras Francji (50000+ km).
            int splitSamples = Math.min(DAY_SPLIT_ELEVATION_SAMPLES_CAP,
                    Math.max(DAY_SPLIT_ELEVATION_SAMPLES,
                            Math.max(prefs.days() != null ? prefs.days() * 3 : 0, full.coordinates().size())));
            ElevationProfile fullProfile = elevation.sample(full.coordinates(), splitSamples);

            setPhase(taskId, "splitting-days");
            checkCancel(taskId);
            List<DaySplitter.DayBoundary> boundaries = daySplitter.splitIntoDays(
                    fullProfile, prefs.days(), prefs.kmPerDay(), prefs.elevationPerDayM(), profile);

            // Cumulative km dla pełnej geometrii BRouter (32k+ punktów) — używane do liczenia
            // REALNEGO dystansu dnia (zamiast nieliniowego rescale × scale z sample-space).
            double[] fullCumKm = cumulativeKm(full.coordinates());

            // Mapowanie SAMPLE-INDEX → FULL-GEOMETRY-INDEX. LocalHgtElevationClient.downsample
            // robi uniform-by-INDEX: fullIdx = round(sampleIdx × (fullSize-1)/(sampleCount-1)).
            // Dlatego MOŻEMY tu odwzorować odwrotność deterministycznie, bez przekazywania
            // mapowania przez port. Stary linear rescale (× realTotal/sampleTotal) był błędny
            // bo zakładał stałą gęstość BRouter points per km — w zakrętach jest ich więcej,
            // na prostej mniej, więc sample-km → real-km NIE jest liniowe i Dzień 5 dostawał
            // 500+ km z transferu Czechy↔Słowacja zamiast realnego dystansu.
            int sampleCount = fullProfile.profile() != null ? fullProfile.profile().size() : 0;
            int fullSize = full.coordinates().size();
            double stepFull = sampleCount > 1 ? (fullSize - 1.0) / (sampleCount - 1.0) : 0.0;

            // Mapowanie planning-waypoint → indeks w pełnej geometrii BRoutera (raz, dla wszystkich dni).
            // Per dzień bierzemy WSZYSTKIE entry-pointy gmin tego dnia jako dayWaypoints (zamiast 48
            // evenly-spaced z pickDayKnots) — user może edytowac każdą zaplanowaną gminę osobno
            // bez utraty creditu sąsiednich.
            int[] wpToFullIdx = mapWaypointsToFullIndices(allWaypoints, full.coordinates());
            int wpMapped = 0;
            for (int v : wpToFullIdx) if (v >= 0) wpMapped++;
            log.info("Waypoint mapping: {}/{} planning waypoints zlokalizowane w pełnej geometrii (reszta = BRouter chunk-fail)",
                    new Object[]{wpMapped, allWaypoints.size()});

            // v3.18 FIX C: indeks pokrycia nad pulą (kandydaci po bbox) — per-dzień ID gmin ZALICZONYCH
            // (kryterium kredytu ≥200 m, port JTS) = źródło prawdy dla kolorowania na froncie (kolor=prawda).
            AreaCoverageIndex coverageIndex = null;
            if (coverageInfo != null) {
                List<UnvisitedArea> coveragePool = new ArrayList<>();
                if (coverageInfo.pickedCandidates() != null)
                    for (AreaCandidate c : coverageInfo.pickedCandidates()) coveragePool.add(c.area);
                if (coverageInfo.reserveCandidates() != null)
                    for (AreaCandidate c : coverageInfo.reserveCandidates()) coveragePool.add(c.area);
                if (!coveragePool.isEmpty()) coverageIndex = coverageIndexFactory.build(coveragePool);
            }
            List<PlanningSessionDay> days = new ArrayList<>(boundaries.size());
            for (int i = 0; i < boundaries.size(); i++) {
                checkCancel(taskId);
                int dayNumber = i + 1;
                setPhase(taskId, "computing-day-" + dayNumber);
                DaySplitter.DayBoundary b = boundaries.get(i);
                int fullStartIdx = (int) Math.round(b.startSampleIdx() * stepFull);
                int fullEndIdx = (int) Math.round(b.endSampleIdx() * stepFull);
                // Inwariant: ostatni dzień KOŃCZY na fullSize-1 (=meta), pierwszy ZACZYNA na 0.
                if (i == 0) fullStartIdx = 0;
                if (i == boundaries.size() - 1) fullEndIdx = fullSize - 1;
                if (fullStartIdx < 0) fullStartIdx = 0;
                if (fullEndIdx >= fullSize) fullEndIdx = fullSize - 1;
                if (fullEndIdx <= fullStartIdx) fullEndIdx = Math.min(fullStartIdx + 1, fullSize - 1);

                double realDistKm = fullCumKm[fullEndIdx] - fullCumKm[fullStartIdx];

                List<double[]> dayGeometry2D = full.coordinates().subList(fullStartIdx, fullEndIdx + 1);
                // Pełna granulacja sampling per dzień — bez tego default cap 500 dawał ~566m między
                // próbkami (dla 283 km dnia) → gubił wzgórki, gain spadał z 1967m → 1304m. Frontend
                // licząc z pełnej geometrii (~23m między coordami) pokazuje prawdziwą wartość.
                ElevationProfile dayElev = elevation.sample(dayGeometry2D, dayGeometry2D.size());
                // ENRICH coords z z (wysokością) — by polyline3D miało realne z, NIE 0. Frontend
                // wtedy używa z BEZPOŚREDNIO (gpsAltitudeAvailable=true) zamiast wołać /route/elevation
                // → ta sama wartość gain w storage i UI, zero rozjazdu. dayElev.profile() ma 1:1 mapowanie
                // po pełnej granulacji (downsample no-op, każdy coord ma swój HGT lookup).
                List<double[]> dayGeometry = new ArrayList<>(dayGeometry2D.size());
                List<double[]> eleProfile = dayElev.profile();
                int eleCount = eleProfile.size();
                for (int p = 0; p < dayGeometry2D.size(); p++) {
                    double[] c = dayGeometry2D.get(p);
                    double z = p < eleCount ? eleProfile.get(p)[1] : 0.0;
                    dayGeometry.add(new double[]{c[0], c[1], z});
                }
                List<Waypoint> dayWaypoints = dayWaypointsFromPlanning(
                        allWaypoints, wpToFullIdx, fullStartIdx, fullEndIdx,
                        full.coordinates(), dayGeometry);

                // Slice stats całej trasy dla okna [fullStartIdx, fullEndIdx] — daje panel "Typy
                // nawierzchni / dróg" gotowy do wyświetlenia per dzień na FE, bez ponownego BRouter call.
                velomarker.entity.RouteStats dayStats = velomarker.service.RouteStatsSlicer.slice(
                        full.stats(), full.coordinates(), fullStartIdx, fullEndIdx);
                // v3.18 FIX C: gminy zaliczone przez geometrię tego dnia (kryterium kredytu portu JTS).
                List<Integer> coveredAreaIds = coverageIndex != null
                        ? new ArrayList<>(coverageIndex.visitedAreaIds(dayGeometry))
                        : java.util.List.of();
                days.add(new PlanningSessionDay(
                        UUID.randomUUID(),
                        session.id(),
                        dayNumber,
                        dayGeometry,
                        dayWaypoints,
                        realDistKm,
                        (int) Math.round(dayElev.gainM()),
                        (int) Math.round(dayElev.lossM()),
                        profile,
                        Instant.now(),
                        dayStats,
                        coveredAreaIds
                ));
            }

            checkCancel(taskId);
            setPhase(taskId, "saving");
            dayRepository.replaceAll(session.id(), days);
            session.setSummary(buildSummary(days, prefs, coverageInfo, full.distanceKm()));
            sessionRepository.save(session);
            PlanningSummary s = session.summary();
            log.info("Plan summary user={} task={} totalKm={} elev={} budget={} verdict={} pool={} (initial={}, iters={}/T{}/G{})",
                    new Object[]{userId, taskId,
                            Math.round(s.totalDistanceKm()), s.totalElevationGain(),
                            s.budgetKm(), s.verdict(),
                            s.poolSize(), s.initialPoolSize(),
                            s.reconcileIters(), s.reconcileTrims(), s.reconcileGrows()});
            // Effort per dzień = km + 0.1 × climb_m (alpha jak ALNS2). Pokazuje czy DaySplitter dobrze
            // wyrównuje. Idealnie wszystkie dni ≈ totalEffort/N. Spread > 5% = sygnał problemu.
            StringBuilder dayDump = new StringBuilder();
            for (PlanningSessionDay d : days) {
                double km = d.distanceKm() != null ? d.distanceKm() : 0.0;
                int climb = d.elevationGain() != null ? d.elevationGain() : 0;
                double effort = km + 0.1 * climb;
                dayDump.append(String.format(java.util.Locale.ROOT, " d%d=%.1fkm/%dm/eff%.0f",
                        d.dayNumber(), km, climb, effort));
            }
            log.info("Day distribution:{}", dayDump);
        } catch (RuntimeException e) {
            throw e;
        } finally {
            java.util.Set<String> missing = missingTilesPerTask.remove(taskId);
            if (missing != null && !missing.isEmpty()) {
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
        }
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

    /** Wynik budowania pul COVERAGE — waypointy + diagnostyka reconcile (do PlanningSummary). */
    public record CoverageBuildInfo(
            List<Waypoint> waypoints,
            int initialPoolSize,
            int finalPoolSize,
            int reconcileIters,
            int reconcileTrims,
            int reconcileGrows,
            BudgetReconciler.Verdict verdict,
            Double baselineKm,
            Double roadAnchors,
            Double roadAreas,
            List<AreaCandidate> pickedCandidates,
            List<AreaCandidate> reserveCandidates,
            List<double[]> baselineGeometry
    ) {}

    private CoverageBuildInfo buildCoverageWaypointsWithInfo(UUID taskId, RoutePreferences prefs, String bearerToken,
                                                  RoadFactorCalibrator calibrator) {
        setPhase(taskId, "fetching-areas");
        checkCancel(taskId);
        List<UnvisitedArea> pool = new ArrayList<>();
        // countryIds[i] + levelIds[i] = i-ta PARA. NIE cartesian product — user wybiera per kraj
        // jeden poziom (PL=gmina, DE=Kreis), różne kraje mogą mieć inne poziomy.
        if (prefs.countryIds() != null && prefs.levelIds() != null) {
            int pairs = Math.min(prefs.countryIds().size(), prefs.levelIds().size());
            for (int i = 0; i < pairs; i++) {
                checkCancel(taskId);
                int countryId = prefs.countryIds().get(i);
                int levelId = prefs.levelIds().get(i);
                pool.addAll(visitClient.listUnvisitedAreas(bearerToken, countryId, levelId, MAX_AREAS_TO_FETCH));
            }
        }
        if (prefs.specialGroupIds() != null) {
            Integer scopedCountryId = (prefs.countryIds() != null && prefs.countryIds().size() == 1)
                    ? prefs.countryIds().get(0) : null;
            for (Integer groupId : prefs.specialGroupIds()) {
                checkCancel(taskId);
                pool.addAll(visitClient.listUnvisitedSpecialAreas(bearerToken, groupId, scopedCountryId, MAX_AREAS_TO_FETCH));
            }
        }
        if (pool.isEmpty()) {
            throw new PlanningSessionNotReadyException("no unvisited areas in scope");
        }

        double[] start = prefs.start().toLngLat();
        boolean loop = Boolean.TRUE.equals(prefs.loop());
        double[] end = loop ? start : (prefs.end() != null ? prefs.end().toLngLat() : null);

        // ETAP 1: PRE-SCREEN BASELINE — BRouter na samych anchorach (start→via→meta).
        // Daje DOLNĄ GRANICĘ trasy. Jeśli sam baseline > budżet → BUDGET_IMPOSSIBLE, user musi
        // poprawić parametry. Inaczej algorytm dalej dobiera obszary do nadkładu.
        setPhase(taskId, "baseline");
        checkCancel(taskId);
        long tBaselineNs = System.nanoTime();
        BaselineProbe baseline = computeBaseline(prefs, resolveProfile(prefs), calibrator);
        long baselineMs = (System.nanoTime() - tBaselineNs) / 1_000_000;
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : 0;
        var baselineVerdict = BudgetReconciler.evaluateBaseline(baseline.distanceKm, prefs.days(), prefs.kmPerDay());
        log.info("Baseline: dist={} km, climb={} m, anchorsStraight={} km, roadAnchors={}, verdict={}",
                new Object[]{Math.round(baseline.distanceKm), Math.round(baseline.climbM),
                        Math.round(baseline.anchorsStraightKm), String.format("%.2f", calibrator.roadAnchors()),
                        baselineVerdict.verdict()});
        log.info("STARTUP TIMING: baseline (BRouter start→meta, liczony RAZ — reużyty w plannerze) = {} ms", baselineMs);
        if (baselineVerdict.verdict() == BudgetReconciler.Verdict.BUDGET_IMPOSSIBLE) {
            throw new PlanningSessionNotReadyException(
                    "Trasa start→via→meta sama waży " + Math.round(baseline.distanceKm) + " km, " +
                    "Twój budżet to " + budgetKm + " km. Zwiększ dni / km na dzień albo wyrzuć via.");
        }

        // Fallback dla road factor (najwyższy poziom administracyjny w puli) — używany jeśli probes zawiodą.
        calibrator.applyAreasFallback(estimateLevelTier(pool));

        // ETAP 2: SNAP-TO-BASELINE — fundamentalnie inny algorytm niż TSP-through-centroids.
        // Bazowa trasa Z BRouter (start→via→meta) JEST gwarantowanym dojazdem do mety. Gminy
        // wstrzelamy MINI-DETORAMI w odpowiednie miejsca bazowej (między najbliższymi punktami
        // geometrii). Punkt wjazdu = ~50 m za granicę gminy w stronę bazowej, NIE centroid.
        // Zaliczenie gminy: BRouter geometry przecina ring — wystarczy MUSNĄĆ.
        setPhase(taskId, "scoring-areas");
        checkCancel(taskId);
        List<double[]> baselineGeom = baseline.geometry;
        log.info("Filtering pool of {} areas by baseline polyline (geom={} points)",
                new Object[]{pool.size(), baselineGeom.size()});

        // Filtruj pulę po bbox bazowej + margines. Margines PROPORCJONALNY do surplus = budget - baseline.
        // User: "co jak ktos da budzet spory - 1800km i sporo wzniosu a baseline bedzie malutki?".
        // Sztywne 30 km bbox ogranicza pulę. Dla surplus 1360 km user ma ~680 km zapasu W BOK -- bbox
        // powinien obejmowac obszary do ~surplus/4 = 340 km. Dla surplus 100 km -- bbox 25 km.
        // Pułap = PRZEKĄTNA puli wybranych krajów (pula jest już country+level-filtered upstream,
        // więc jej bbox = realny zasięg tego co zbieramy). Bbox to TYLKO pre-filtr — selekcja i tak
        // bierze najbliższe — więc nie ma sensu sztywne 200/500/1000. Mały budżet → surplus/4 (ciasno),
        // duży budżet → aż do pełnego zasięgu krajów (cała Polska ze Skierniewic itp.). MAX_POOL niżej
        // pilnuje wydajności dla gęstych krajów.
        double poolDiagKm = 200;
        if (!pool.isEmpty()) {
            double mnLng = Double.MAX_VALUE, mnLat = Double.MAX_VALUE, mxLng = -Double.MAX_VALUE, mxLat = -Double.MAX_VALUE;
            for (UnvisitedArea a : pool) {
                mnLng = Math.min(mnLng, a.lng()); mxLng = Math.max(mxLng, a.lng());
                mnLat = Math.min(mnLat, a.lat()); mxLat = Math.max(mxLat, a.lat());
            }
            poolDiagKm = WaypointSelector.haversineKm(new double[]{mnLng, mnLat}, new double[]{mxLng, mxLat});
        }
        double surplusForBbox = Math.max(50, budgetKm - baseline.distanceKm);
        double bboxRadiusKm = Math.max(30, Math.min(poolDiagKm, surplusForBbox / 4.0));
        // 1° lat ≈ 111 km zawsze; 1° lng = 111 × cos(lat) km — kurczy się ku biegunom.
        // Stała 1/100 ignorowała cos(lat) → na 52°N margin lng był liczony 13.4° zamiast prawidłowych
        // ~9.2°, a właściwie odwrotnie: dawano 9.17° (== km/100) zamiast 13.4° (== km/(111×cos52°)),
        // skutkiem czego wschodnia PL (lng>22.6°) leciała za bbox mimo że odległość km mieściła się.
        double centerLat = baselineGeom.stream().mapToDouble(p -> p[1]).average().orElse(52.0);
        double bboxMarginLatDeg = bboxRadiusKm / 111.0;
        double bboxMarginLngDeg = bboxRadiusKm / (111.0 * Math.cos(Math.toRadians(centerLat)));
        double[] bbox = polylineBbox(baselineGeom, bboxMarginLngDeg, bboxMarginLatDeg);
        log.info("Bbox filter radius: {} km (surplus {} km / 4, cap=pool diagonal {} km, marginLng={}° marginLat={}° @lat={}°)",
                new Object[]{Math.round(bboxRadiusKm), Math.round(surplusForBbox), Math.round(poolDiagKm),
                        String.format(java.util.Locale.ROOT, "%.2f", bboxMarginLngDeg),
                        String.format(java.util.Locale.ROOT, "%.2f", bboxMarginLatDeg),
                        String.format(java.util.Locale.ROOT, "%.1f", centerLat)});
        // DEM pre-fault dla bbox planu — best-effort, nigdy nie blokuje planowania.
        try {
            elevation.preload(bbox);
        } catch (RuntimeException e) {
            log.warn("DEM preload nieudany (ignoruję): {}", e.getMessage());
        }
        List<UnvisitedArea> nearPool = new ArrayList<>();
        for (UnvisitedArea a : pool) {
            if (a.lng() >= bbox[0] && a.lng() <= bbox[2]
                    && a.lat() >= bbox[1] && a.lat() <= bbox[3]) {
                nearPool.add(a);
            }
        }
        // Guard rozmiaru puli: scoring (FAZA1) i reward NN są O(N×…); dla gęstych krajów (FR ~35k)
        // przy capie 500 km pula eksploduje → planowanie wolne. Truncate do MAX_POOL najbliższych
        // baseline (po nearest-vertex na subsamplowanym baseline = tanio). PL (2479) nigdy nie tnie.
        final int MAX_POOL = 80000;
        if (nearPool.size() > MAX_POOL) {
            int bstep = Math.max(1, baselineGeom.size() / 400);
            nearPool.sort(Comparator.comparingDouble(a -> {
                double best = Double.MAX_VALUE;
                for (int j = 0; j < baselineGeom.size(); j += bstep) {
                    double d = WaypointSelector.haversineKm(
                            new double[]{a.lng(), a.lat()}, baselineGeom.get(j));
                    if (d < best) best = d;
                }
                return best;
            }));
            log.warn("Bbox pool {} > {} (gęsty kraj + duży budżet) — truncate do {} najbliższych baseline",
                    new Object[]{nearPool.size(), MAX_POOL, MAX_POOL});
            nearPool = new ArrayList<>(nearPool.subList(0, MAX_POOL));
        }
        log.info("Bbox filter: {} → {} (within ~{} km of baseline)",
                new Object[]{pool.size(), nearPool.size(), Math.round(bboxRadiusKm)});
        if (nearPool.isEmpty()) {
            log.warn("No areas near baseline corridor — returning baseline-only trip");
            return new CoverageBuildInfo(buildAnchorWaypoints(prefs), 0, 0, 0, 0, 0,
                    BudgetReconciler.Verdict.SURPLUS, baseline.distanceKm,
                    calibrator.roadAnchors(), calibrator.roadAreas(),
                    new ArrayList<>(), new ArrayList<>(), baseline.geometry);
        }

        // Dla każdej gminy: distance do najbliższego segmentu bazowej + intersection check + entry point.
        List<AreaCandidate> candidates = new ArrayList<>(nearPool.size());
        for (UnvisitedArea a : nearPool) {
            checkCancel(taskId);
            AreaCandidate c = scoreAreaAgainstBaseline(a, baselineGeom);
            candidates.add(c);
        }
        // Sort by detourStraightKm ASC (zaliczone darmowo = 0).
        candidates.sort((x, y) -> Double.compare(x.detourStraightKm, y.detourStraightKm));
        int alreadyIntersected = (int) candidates.stream().filter(c -> c.intersected).count();
        log.info("Scored {} candidates: {} already intersected by baseline (free credits)",
                new Object[]{candidates.size(), alreadyIntersected});

        // DENSITY-AWARE RE-SORT (regret-style diversity).
        // Greedy ASC po detour ma tendencje: wez wszystkie najtansze obszary z gestego klastra
        // (np. wokol Brna) ZANIM dotrzesz do drozszych ale izolowanych w innym regionie.
        // Skutek: petle wokol jednego klastra + brak pokrycia dalekich rejonow + zbedne
        // "slepe glupie przejazdy" przez te same okolice. Fix: zlicz numNearby per candidate
        // (w promieniu 5 km) i re-sort po effectiveCost = detour * (1 + numNearby * 0.1).
        // Izolowane (numNearby ~ 0) zachowuja detour; geste klastry dostaja mnoznik > 1 -> ida pozniej.
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
            SpatialGrid grid = new SpatialGrid(pts);
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

        // ETAP 3: DENSITY PROBE — BRouter na 30 najbliższych gminach do bazowej → roadAreas.
        String coverageProfile = resolveProfile(prefs);
        long tProbeNs = System.nanoTime();
        densityProbeFromCandidates(taskId, candidates, baselineGeom, coverageProfile, calibrator);
        log.info("STARTUP TIMING: density-probe (BRouter ~30 obszarów) = {} ms", (System.nanoTime() - tProbeNs) / 1_000_000);

        // ETAP 4: GREEDY ADD aż suma detourReal ≈ surplus × 1.0.
        // Już-przecięte gminy są "darmowe" (detour=0), reszta sortowana ASC po koszcie.
        // Było × 0.95 (safety margin) — przejście na × 1.0 bo BudgetReconciler dalej w pipeline
        // TRIM-uje overshoot, a zostawianie 5% surplusa daje 100+ km pustki w budżecie (real
        // detour BRouter wychodzi mniejszy niż estymata detourStraight × roadAreas → finalna
        // suma spada dodatkowo poniżej 0.95 × budżet).
        double surplusKm = budgetKm - baseline.distanceKm;
        double roadAreas = calibrator.roadAreas();
        double targetExtra = surplusKm;
        double usedExtra = 0;
        List<AreaCandidate> picked = new ArrayList<>();
        List<AreaCandidate> reserve = new ArrayList<>();
        int freeCount = 0;
        int paidCount = 0;

        // ADAPTIVE FAIRNESS: 2-etapowy greedy.
        // Etap 1: AFFORDABLE NN-weighted min-coverage greedy per kategoria (countryId:levelId:specialGroupId).
        //   Tylko obszary z detour ≤ median × 1.0 lub intersected. Z bucketu kategorii bierzemy
        //   peekFirst (cheapest po sort detour ASC). Wybór KATEGORII: ta o najmniejszym sumarycznym
        //   coverageReachKm (= Σ avgNN_cat × picked_count). NIE cykliczny round-robin — wybieramy
        //   zawsze najbardziej „głodną" kategorię po zasięgu geograficznym. Naturalnie balansuje
        //   gęste (CZ Obec nn~5km) vs rzadkie (DE Kreissitz nn~55km): rzadkie zbierają więcej
        //   pozycji proporcjonalnie do reach, niezależnie od fizycznej powierzchni (Kreissitz fix).
        // Etap 2: EXPENSIVE greedy ASC (bez fairness).
        //   Pozostałe (drogie) obszary, dorzucane gdy budget zostaje. SK token area wejdzie tylko
        //   jeśli budżet pozwoli, bez forsowania.
        double[] nonZeroDetours = candidates.stream()
                .filter(c -> !c.intersected && c.detourStraightKm > 0)
                .mapToDouble(c -> c.detourStraightKm).sorted().toArray();
        double medianDetour = nonZeroDetours.length > 0
                ? nonZeroDetours[nonZeroDetours.length / 2] : 0;
        // Threshold = median × 1.0 (nie × 2.0). User: "nie lepiej zbierać blisko trasy
        // i więcej, nie robiąc takich długich wypadów?" -- tighter threshold pcha obszary
        // > median do expensive pool, zostają tylko bliskie. Dla mediany 56km, threshold 56km
        // (zamiast 112km) → CZ Obec daleko od korytarza idą do reserve, nie zaśmiecają picked.
        double affordableThreshold = medianDetour * 1.0;
        java.util.Map<String, java.util.Deque<AreaCandidate>> bucketed = new java.util.LinkedHashMap<>();
        java.util.List<AreaCandidate> expensivePool = new java.util.ArrayList<>();
        for (AreaCandidate c : candidates) {
            if (c.intersected || c.detourStraightKm <= affordableThreshold) {
                String key = c.area.countryId() + ":" + c.area.levelId() + ":" + c.area.specialGroupId();
                bucketed.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(c);
                c.setWasAffordable(true);   // Fix 16: dla diagnostyki GROW source
            } else {
                expensivePool.add(c);
                c.setWasAffordable(false);
            }
        }
        StringBuilder bucketLog = new StringBuilder();
        for (var e : bucketed.entrySet()) {
            if (bucketLog.length() > 0) bucketLog.append(", ");
            bucketLog.append(e.getKey()).append("=").append(e.getValue().size());
        }
        log.info("Affordable buckets (detour ≤ {} km, median*2): {} | expensive: {}",
                new Object[]{String.format("%.1f", affordableThreshold), bucketLog, expensivePool.size()});
        // NN-WEIGHTED MIN-COVERAGE GREEDY: każdą iterację wybieramy kategorię o NAJMNIEJSZYM
        // sumarycznym coverageReachKm (= Σ avgNN_cat × picked_count). To NIE jest round-robin
        // (cykliczne A→B→C→A) — to wybór „najbardziej głodnej" kategorii po geograficznym zasięgu.
        // Dla par o tej samej powierzchni ale różnej rzadkości (Kreissitz 30 km²/60km NN vs obec
        // 30 km²/5km NN) Kreissitz dostaje ~12× więcej pozycji = realny balans geograficzny niezależny
        // od fizycznej powierzchni gminy. Dawniej balansowaliśmy po areaKm² — działało dla Landkreis
        // (large+sparse) vs obec (small+dense), ale myliło Kreissitz (small+sparse) z obec.
        // avgNN_cat precomputowane raz, spójne z reward calibration w AlnsCoveragePlanner2 (~linia 1995).
        java.util.Map<String, Double> avgNNcat = new java.util.HashMap<>();
        for (var e : bucketed.entrySet()) {
            List<velomarker.entity.planning.UnvisitedArea> catAreas = e.getValue().stream()
                    .map(c -> c.area).toList();
            double nn = velomarker.service.planning.alns2.GminaIndex.avgNearestNeighborDistKm(catAreas);
            if (nn <= 0) {
                // Fallback dla kategorii z <2 obszarami (avgNN==0) — sqrt(area) jako proxy spacing,
                // żeby kategoria nie miała wagi 0 i nie była wieczym min (zawsze wybierana).
                nn = catAreas.isEmpty() ? 1.0
                        : Math.max(1.0, Math.sqrt(Math.max(1.0, catAreas.get(0).areaKm2())));
            }
            avgNNcat.put(e.getKey(), nn);
        }
        java.util.Map<String, Double> coverageReachKm = new java.util.HashMap<>();
        for (String key : bucketed.keySet()) coverageReachKm.put(key, 0.0);
        while (true) {
            // Wybierz kategorię z MIN coverage reach (sposrod tych z niepustym bucketem)
            String pickCat = null;
            double minCoverage = Double.MAX_VALUE;
            for (var entry : bucketed.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                double cov = coverageReachKm.get(entry.getKey());
                if (cov < minCoverage) {
                    minCoverage = cov;
                    pickCat = entry.getKey();
                }
            }
            if (pickCat == null) break; // wszystkie buckety puste
            AreaCandidate c = bucketed.get(pickCat).peekFirst();
            double detourReal = c.intersected ? 0 : (c.detourStraightKm * roadAreas);
            if (usedExtra + detourReal > targetExtra && !c.intersected) {
                // Iter 8 BUGFIX: zamiast `clear()` (które gubiło drained affordable), DRAIN do reserve.
                // Wczesniej: greedy zatrzymywał się przy targetExtra → bucket cleared → reserve miał
                // TYLKO expensive (> median × 1.0 od baseline) → GROW musiał brać daleko (75% wp
                // w 50-100 km od baseline). Teraz drained leci do reserve i GROW shell expansion
                // ma BLISKIE obszary do dorzucenia.
                reserve.addAll(bucketed.get(pickCat));
                bucketed.get(pickCat).clear();
                continue;
            }
            bucketed.get(pickCat).pollFirst();
            picked.add(c);
            usedExtra += detourReal;
            coverageReachKm.merge(pickCat, avgNNcat.get(pickCat), Double::sum);
            if (c.intersected) freeCount++; else paidCount++;
        }
        // Drain pozostałe affordable do reserve (przekraczają budget per category)
        for (var bucket : bucketed.values()) reserve.addAll(bucket);
        // Log per-category NN-reach dla diagnostyki (sumaryczny picked × avgNN; w nawiasie avgNN_cat).
        StringBuilder covLog = new StringBuilder();
        for (var e : coverageReachKm.entrySet()) {
            if (covLog.length() > 0) covLog.append(", ");
            covLog.append(e.getKey())
                    .append("=").append(Math.round(e.getValue())).append("km")
                    .append(" (nn=").append(String.format(java.util.Locale.ROOT, "%.1f", avgNNcat.get(e.getKey())))
                    .append(")");
        }
        log.info("NN-reach picks per category: {}", covLog);
        int affordablePicked = picked.size();
        // Etap 2: EXPENSIVE greedy ASC -- dorzuca cheapest gdy budget zostaje
        expensivePool.sort((a, b) -> Double.compare(a.detourStraightKm, b.detourStraightKm));
        int expensiveAdded = 0;
        for (AreaCandidate c : expensivePool) {
            double detourReal = c.intersected ? 0 : (c.detourStraightKm * roadAreas);
            if (usedExtra + detourReal > targetExtra) {
                reserve.add(c);
                continue;
            }
            picked.add(c);
            usedExtra += detourReal;
            expensiveAdded++;
            paidCount++;
        }
        log.info("Greedy pick: {} affordable ({} free + {} paid) + {} expensive, total detour ~{} km (target {} km), reserve={}",
                new Object[]{affordablePicked, freeCount, paidCount - expensiveAdded, expensiveAdded,
                        Math.round(usedExtra), Math.round(targetExtra), reserve.size()});

        // PRE-BROUTER DEDUP: flaguje (NIE usuwa z listy) obszary których entry-points są w ringach
        // sąsiadów. Iter 9 Fix #1: zostają w picked list (= raportowane), tylko nie dostaja wp w tour.
        picked = dedupByMutualCoverage(picked);
        long mutuallyCoveredCount = picked.stream().filter(AreaCandidate::isMutuallyCoveredByNeighbor).count();
        if (mutuallyCoveredCount > 0) {
            log.info("Pre-BRouter mutual dedup: {} flagged as mutually-covered (zostają w picked, bez explicit wp)",
                    new Object[]{mutuallyCoveredCount});
        }

        // Sort picked by insertionIdx → naturalna kolejność wzdłuż bazowej.
        picked.sort((x, y) -> Integer.compare(x.insertionIdx, y.insertionIdx));

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
            anchorIndices[i] = findNearestGeomIdx(baselineGeom, anchorWps.get(i).toLngLat());
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

        log.info("Final waypoints: {} (= {} anchors + {} gminy entry-points)",
                new Object[]{result.size(), anchorWps.size(), picked.size()});
        logCategoryBreakdown(picked.stream().map(c -> c.area).toList());

        return new CoverageBuildInfo(result, candidates.size(), picked.size(),
                0, 0, 0, BudgetReconciler.Verdict.OK,
                baseline.distanceKm, calibrator.roadAnchors(), calibrator.roadAreas(),
                picked, reserve, baseline.geometry);
    }

    /** Waypointy z samych anchorów (gdy korytarz pusty / pula gmin pusta). */
    private static List<Waypoint> buildAnchorWaypoints(RoutePreferences prefs) {
        List<Waypoint> wps = new ArrayList<>();
        wps.add(prefs.start());
        if (prefs.via() != null) wps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) wps.add(prefs.start());
        else if (prefs.end() != null) wps.add(prefs.end());
        else wps.add(prefs.start());
        return wps;
    }

    /** Wynik scoringu gminy względem bazowej trasy. */
    public static class AreaCandidate {
        final UnvisitedArea area;
        final boolean intersected;     // baseline polyline już przecina ring → gmina zaliczona darmo
        final int insertionIdx;        // indeks w baselineGeom najbliższy gminie (utrzymuje kolejność wzdłuż bazowej)
        final double distToBaselineKm; // straight haversine od ringu do bazowej (0 jeśli intersected)
        final double detourStraightKm; // szacowany nadkład straight: 2× distToBaseline + 0.2 km (wjazd+wyjazd)
        final double entryLng;
        final double entryLat;
        /**
         * Iteracja 8 / Fix 16: true jeśli obszar był w `bucketed` (affordable: detour ≤ median × 1.0).
         * false jeśli był w `expensivePool`. Ustawiany w `buildCoverageWaypointsWithInfo` po
         * podziale na buckety. Używany w TSP do diagnostyki GROW source breakdown.
         */
        boolean wasAffordable = false;
        /**
         * Iter 9 Fix #1: true jeśli `dedupByMutualCoverage` uznał ten obszar za "covered by neighbor"
         * (entry-point sąsiada w ringu LUB ring przecięty przez segment sąsiadów). AREA ZOSTAJE w
         * `picked` list (= raportowana jako zaliczona), ale NIE dostaje waypointu w tour (trasa
         * BRouter naturalnie przejdzie przez nią). User: "biale dziury blisko trasy" — to były te
         * obszary które mutual dedup usuwał z picked completely.
         */
        boolean mutuallyCoveredByNeighbor = false;

        // Gettery dla testów (intersected, insertionIdx, detourStraightKm, entryLng/Lat).
        public boolean isIntersected() { return intersected; }
        public int getInsertionIdx() { return insertionIdx; }
        public double getDistToBaselineKm() { return distToBaselineKm; }
        public double getDetourStraightKm() { return detourStraightKm; }
        public double getEntryLng() { return entryLng; }
        public double getEntryLat() { return entryLat; }
        public UnvisitedArea getArea() { return area; }
        public boolean wasAffordable() { return wasAffordable; }
        public void setWasAffordable(boolean v) { this.wasAffordable = v; }
        public boolean isMutuallyCoveredByNeighbor() { return mutuallyCoveredByNeighbor; }
        public void setMutuallyCoveredByNeighbor(boolean v) { this.mutuallyCoveredByNeighbor = v; }

        public AreaCandidate(UnvisitedArea area, boolean intersected, int insertionIdx, double distToBaselineKm,
                      double detourStraightKm, double entryLng, double entryLat) {
            this.area = area;
            this.intersected = intersected;
            this.insertionIdx = insertionIdx;
            this.distToBaselineKm = distToBaselineKm;
            this.detourStraightKm = detourStraightKm;
            this.entryLng = entryLng;
            this.entryLat = entryLat;
        }
    }

    /**
     * Liczy dla gminy: czy bazowa już ją przecina, indeks insertion, distance, entry point ~50m
     * za granicę w stronę bazowej. Entry point używamy w final BRouter — minimalny detour zamiast
     * jazdy do centroidu.
     */
    static AreaCandidate scoreAreaAgainstBaseline(UnvisitedArea area, List<double[]> baselineGeom) {
        double[] centroid = {area.lng(), area.lat()};
        // Najbliższy punkt bazowej do centroidu (z indeksem).
        double minDist = Double.MAX_VALUE;
        int nearestIdx = 0;
        for (int i = 0; i < baselineGeom.size(); i++) {
            double[] p = baselineGeom.get(i);
            double d = WaypointSelector.haversineKm(p, centroid);
            if (d < minDist) {
                minDist = d;
                nearestIdx = i;
            }
        }
        double[] nearestPoint = baselineGeom.get(nearestIdx);

        // Intersection: czy KTÓRYŚ z punktów bazowej leży wewnątrz ringa? (szybki test;
        // dla dokładności matematycznej trzeba by sprawdzać przecięcia segmentów, ale dla gmin
        // z ringiem N=20-50 punktów i baseline N=30 000 wystarczy point-in-ring test).
        boolean intersected = false;
        if (area.ring() != null && area.ring().length >= 3) {
            // Sprawdzamy ±300 punktów wokół nearestIdx — jeśli żaden nie leży w ringu, gmina nie przecina.
            int from = Math.max(0, nearestIdx - 300);
            int to = Math.min(baselineGeom.size(), nearestIdx + 300);
            for (int i = from; i < to; i++) {
                if (WaypointSelector.pointInRing(baselineGeom.get(i), area.ring())) {
                    intersected = true;
                    break;
                }
            }
        }

        // Entry point: ~150 m do wewnatrz ringa, w strone nearestPoint od najblizszego ring point.
        // Bylo 50m -- BRouter czesto slizgal sie wzdluz granicy ringa i zawracal bez wjazdu
        // (user widzial "wjazd pod granice i chuj"). 150m = bezpieczna glebokosc zaliczenia.
        // Limit 1/2 dystansu ringPt->centroid: dla bardzo malych gmin (<300m promienia) nie
        // wciskaj punktu za centroid w naprzeciwlegly rog.
        double entryLng = area.lng();
        double entryLat = area.lat();
        if (area.ring() != null && area.ring().length >= 3) {
            double[] ringPt = closestRingPoint(area.ring(), nearestPoint);
            double dxLng = centroid[0] - ringPt[0];
            double dyLat = centroid[1] - ringPt[1];
            double len = Math.sqrt(dxLng * dxLng + dyLat * dyLat);
            if (len > 1e-9) {
                // 0.0015 deg ~ 150 m. Cap na ½ dystansu do centroidu (małe gminy).
                double rawStepDeg = Math.min(0.0015, len / 2.0);
                double stepDeg = rawStepDeg / len;
                entryLng = ringPt[0] + dxLng * stepDeg;
                entryLat = ringPt[1] + dyLat * stepDeg;
            } else {
                entryLng = ringPt[0];
                entryLat = ringPt[1];
            }
        }

        double detour = intersected ? 0 : (2 * minDist + 0.2);
        return new AreaCandidate(area, intersected, nearestIdx, minDist, detour, entryLng, entryLat);
    }

    /** Najbliższy wierzchołek ringa do punktu p. */
    private static double[] closestRingPoint(double[][] ring, double[] p) {
        double[] best = ring[0];
        double bestD = Double.MAX_VALUE;
        for (double[] r : ring) {
            double d = WaypointSelector.haversineKm(r, p);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    /**
     * Bbox polyline z OSOBNYMI marginesami lng/lat (w stopniach). [minLng, minLat, maxLng, maxLat].
     * Osobne marginesy konieczne bo 1° lng ≠ 1° lat (lng kurczy się ku biegunom przez cos(lat)).
     */
    static double[] polylineBbox(List<double[]> poly, double marginLngDeg, double marginLatDeg) {
        double minLng = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (double[] p : poly) {
            if (p[0] < minLng) minLng = p[0];
            if (p[0] > maxLng) maxLng = p[0];
            if (p[1] < minLat) minLat = p[1];
            if (p[1] > maxLat) maxLat = p[1];
        }
        return new double[]{minLng - marginLngDeg, minLat - marginLatDeg,
                            maxLng + marginLngDeg, maxLat + marginLatDeg};
    }

    /** Indeks punktu w polyline najbliższy zadanej pozycji (lng, lat). */
    static int findNearestGeomIdx(List<double[]> geom, double[] target) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < geom.size(); i++) {
            double d = WaypointSelector.haversineKm(geom.get(i), target);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Density probe z 30 najbliższych kandydatów (do bazowej). Daje calibrator.roadAreas(). */
    private void densityProbeFromCandidates(UUID taskId, List<AreaCandidate> sortedCandidates,
                                            List<double[]> baselineGeom, String profile,
                                            RoadFactorCalibrator calibrator) {
        if (sortedCandidates.isEmpty() || baselineGeom.size() < 2) return;
        setPhase(taskId, "density-probe");
        checkCancel(taskId);
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
            calibrator.applyAreasProbe(r.distanceKm(), straight);
            log.info("Density probe: {} areas, real={} km, straight={} km → roadAreas={}",
                    new Object[]{sample.size(), Math.round(r.distanceKm()), Math.round(straight),
                            String.format("%.2f", calibrator.roadAreas())});
        } catch (RuntimeException e) {
            log.warn("Density probe failed ({}), keeping fallback roadAreas={}",
                    e.getMessage(), String.format("%.2f", calibrator.roadAreas()));
        }
    }

    /** Snapshot probe baseline (anchors-only BRouter + elevation). Geometry potrzebna dla snap-to-baseline. */
    private record BaselineProbe(double distanceKm, double climbM, double anchorsStraightKm, List<double[]> geometry) {}

    /** Wynik trace'owania (BRouter calc + finalna lista waypointów po dedup/trim). */
    private record TraceResult(RouteCalculation calc, List<Waypoint> finalWaypoints) {}

    /**
     * BRouter chunked + adaptive loop (max 5 iteracji): TRIM gdy meta nieosiągnięta, GROW gdy
     * budżetu zostało, DEDUP gdy gminy pokryte naturalnie. Anty-oscylacja: gmina raz wyrzucona
     * przez trim NIE wraca przez grow w tej samej sesji (tylko gminy NIGDY-NIE-PRÓBOWANE z reserve).
     *
     * <p>State maszyna:
     * <pre>
     *   currentPicked = greedy picked (full set z buildCoverage)
     *   droppedPool = []  (gminy wyrzucone przez trim — NIE wracają)
     *   reservePool = []  (gminy które nie weszły w greedy — można dodać przez grow)
     *   loop max 5×:
     *     BRouter
     *     if endGap > maxGap → TRIM 25% najdroższych z currentPicked → droppedPool
     *     elif totalKm > budget × 1.10 → TRIM 15% najdroższych (overflow)
     *     elif totalKm &lt; budget × 0.85 AND reservePool nonempty → GROW 15% najtańszych z reservePool
     *     else → OK + DEDUP gmin pokrytych naturalnie (1 raz max)
     * </pre>
     */
    private TraceResult routeWithTrimAndDedup(UUID taskId, RoutePreferences prefs, List<Waypoint> waypoints,
                                              CoverageBuildInfo coverageInfo, String profile,
                                              RoadFactorCalibrator calibrator, double maxGapKm) {
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : Integer.MAX_VALUE;
        double upperBudget = budgetKm * 1.10;
        double lowerBudget = budgetKm * 0.85;
        double growTargetKm = budgetKm * 0.95;

        List<Waypoint> currentWps = new ArrayList<>(waypoints);
        List<AreaCandidate> currentPicked = coverageInfo != null
                ? new ArrayList<>(coverageInfo.pickedCandidates()) : new ArrayList<>();
        List<AreaCandidate> reservePool = coverageInfo != null
                ? buildReservePoolFromUnpicked(coverageInfo) : new ArrayList<>();
        List<AreaCandidate> droppedPool = new ArrayList<>();

        RouteCalculation calc = null;
        // lastGood = stan w którym (a) meta osiągnięta, (b) actualKm ≤ upperBudget.
        // Tylko taki stan jest BEZPIECZNY do zaakceptowania jako wynik.
        TraceResult lastGood = null;
        boolean dedupTried = false;
        boolean emaUpdated = false;
        boolean justGrew = false; // flaga rollback'u — jeśli grow spowoduje overflow, wracamy do lastGood

        for (int attempt = 0; attempt < 8; attempt++) {
            checkCancel(taskId);
            List<double[]> input = currentWps.stream().map(Waypoint::toLngLat).toList();
            calc = calculateRouteChunked(taskId, input, profile);

            if (!emaUpdated && coverageInfo != null && coverageInfo.baselineKm() != null) {
                double fullStraight = waypointSelector.straightLineDistanceKm(input);
                double anchorsStraight = computeAnchorsStraight(currentWps);
                double extraStraight = Math.max(0, fullStraight - anchorsStraight);
                double extraReal = Math.max(0, calc.distanceKm() - coverageInfo.baselineKm());
                if (extraStraight > 1.0) calibrator.updateAreasFromActual(extraReal, extraStraight);
                emaUpdated = true;
            }

            double endGap = computeEndGap(calc.coordinates(), prefs);
            double actualKm = calc.distanceKm();
            log.info("Routing iter={} wp={} actualKm={} budget=[{}..{}] endGap={} km picked={} dropped={} reserve={}",
                    new Object[]{attempt, currentWps.size(), Math.round(actualKm),
                            Math.round(lowerBudget), Math.round(upperBudget),
                            String.format("%.2f", endGap),
                            currentPicked.size(), droppedPool.size(), reservePool.size()});

            // 1) META NIE OSIĄGNIĘTA → trim drogie z picked.
            if (endGap > maxGapKm) {
                if (currentPicked.isEmpty()) {
                    log.warn("Meta not reached and no gminy to trim — falling through to assert");
                    break;
                }
                if (justGrew && lastGood != null) {
                    // Grow zepsuł meta — rollback do ostatniego dobrego stanu.
                    log.warn("Grow spowodował meta-fail (endGap={} km) — rollback do lastGood ({} km, {} wp)",
                            new Object[]{String.format("%.2f", endGap),
                                    Math.round(lastGood.calc().distanceKm()),
                                    lastGood.finalWaypoints().size()});
                    return lastGood;
                }
                int beforeTrim = currentPicked.size();
                trimByDetourFromCurrent(currentPicked, droppedPool, 0.25);
                currentWps = buildWaypointsFromPicked(prefs, sortByInsertionIdx(currentPicked),
                        coverageInfo.baselineGeometry());
                log.warn("TRIM: meta not reached → wyrzucam {} drogich gmin ({} → {})",
                        new Object[]{beforeTrim - currentPicked.size(), beforeTrim, currentPicked.size()});
                justGrew = false;
                continue;
            }

            // 2) META OK ale BUDGET OVERFLOW → trim 15% najdroższych.
            if (actualKm > upperBudget) {
                if (justGrew && lastGood != null) {
                    // Grow przepalił budżet — rollback do ostatniego dobrego stanu.
                    log.info("Grow spowodował overflow ({} > {}) — rollback do lastGood ({} km, {} wp)",
                            new Object[]{Math.round(actualKm), Math.round(upperBudget),
                                    Math.round(lastGood.calc().distanceKm()),
                                    lastGood.finalWaypoints().size()});
                    return lastGood;
                }
                int beforeTrim = currentPicked.size();
                trimByDetourFromCurrent(currentPicked, droppedPool, 0.15);
                if (currentPicked.size() == beforeTrim) {
                    log.info("Overflow ({} > {}) ale nic do trim — akceptujemy", new Object[]{Math.round(actualKm), Math.round(upperBudget)});
                    // Akceptujemy stan z poprzedniego iter jeśli był OK.
                    if (lastGood != null) return lastGood;
                    break;
                }
                currentWps = buildWaypointsFromPicked(prefs, sortByInsertionIdx(currentPicked),
                        coverageInfo.baselineGeometry());
                log.info("TRIM-OVERFLOW: actualKm {} > {} → wyrzucam {} drogich ({} → {})",
                        new Object[]{Math.round(actualKm), Math.round(upperBudget),
                                beforeTrim - currentPicked.size(), beforeTrim, currentPicked.size()});
                justGrew = false;
                continue;
            }

            // 3) META OK + actualKm in [0, upperBudget] -> STAN DOBRY.
            // Zapisz lastGood tylko jesli BLIZEJ growTargetKm niz dotychczasowy best.
            // Bez tego DEDUP po dobrym iter (np. 1699 km) prowadzi do iter (750 km) ktory
            // overwrite'uje lastGood -- final wynik to 750 km mimo ze mielismy 1699 km na talerzu.
            // Logika "best": minimum |actualKm - growTargetKm|. growTargetKm = 0.95 x budget.
            double curDistToTarget = Math.abs(actualKm - growTargetKm);
            double bestDistToTarget = lastGood != null
                    ? Math.abs(lastGood.calc().distanceKm() - growTargetKm)
                    : Double.MAX_VALUE;
            if (curDistToTarget < bestDistToTarget) {
                lastGood = new TraceResult(calc, currentWps);
            }
            justGrew = false;

            // 4) Jesli zostalo budzetu -> GROW konserwatywny (do remainingStraight x roadAreas).
            // Warunek bylo: actualKm < lowerBudget (0.85 x budget) -- zatrzymywal sie 15% pod
            // budzetem (user widzial 1606/1800 = 11% pustki). Teraz: actualKm < growTargetKm
            // (0.95 x budget), zeby zblizyc sie do budzetu (max ~5% pustki).
            if (actualKm < growTargetKm && !reservePool.isEmpty()) {
                // GROW PRE-FILTER: usun z reserve obszary juz NATURALNIE przeciete przez aktualny
                // slad BRouter. Bez tego GROW dodaje je jako waypoint -> BRouter robi petle przez
                // gmine ktora i tak by zaliczyl jadac normalnie (user widzial "dwa razy ta sama
                // gmine roznymi drogami"). Logika intersection ta sama co w removeNaturallyCoveredFromPicked.
                int reserveBefore = reservePool.size();
                final List<double[]> curGeometry = calc.coordinates();
                reservePool.removeIf(c -> isAreaCoveredByGeometry(c.area, curGeometry));
                int naturallyCovered = reserveBefore - reservePool.size();
                if (naturallyCovered > 0) {
                    log.info("GROW pre-filter: {} obszarow z reserve naturalnie zaliczonych -> pomijam ({} -> {})",
                            new Object[]{naturallyCovered, reserveBefore, reservePool.size()});
                }
                if (reservePool.isEmpty()) {
                    // Po pre-filter nie zostalo nic do GROW -- albo wszystko zaliczone naturalnie,
                    // albo reszta to obszary zbyt drogie. Koniec.
                    break;
                }
                double remainingKm = growTargetKm - actualKm;
                double remainingStraight = remainingKm / Math.max(1.0, calibrator.roadAreas());
                int before = currentPicked.size();
                int added = growUntilBudget(currentPicked, reservePool, remainingStraight);
                if (added > 0) {
                    currentWps = buildWaypointsFromPicked(prefs, sortByInsertionIdx(currentPicked),
                            coverageInfo.baselineGeometry());
                    log.info("GROW: actualKm {} < {} -> dorzucam {} tanszych (cap ~{} km straight, {} -> {})",
                            new Object[]{Math.round(actualKm), Math.round(growTargetKm), added,
                                    Math.round(remainingStraight), before, currentPicked.size()});
                    justGrew = true;
                    continue;
                }
            }

            // 5) DEDUP -- gminy pokryte naturalnie -> usun z `currentPicked`.
            // GUARD: jesli DEDUP usuwa > 30% entry-pointow -- rollback do snapshot i break.
            // Window +-300 punktow w removeNaturallyCoveredFromPicked jest zbyt liberalny dla
            // duzej geometrii (40k punktow BRouter -> ~3 km okno, lapie obszary ktore tak
            // naprawde NIE sa naturalnie zaliczone). Skutek bez guardu: 269 -> 105 (-61%) ->
            // iter=7 BRouter krotszy o polowe -> lastGood=best wybiera state przed DEDUP, ale
            // mozemy wczesniej skonczyc petle (oszczednosc iter GROW + czytelniejsze logi).
            if (!dedupTried && coverageInfo != null && !currentPicked.isEmpty()) {
                dedupTried = true;
                int before = currentPicked.size();
                List<AreaCandidate> snapshot = new ArrayList<>(currentPicked);
                removeNaturallyCoveredFromPicked(currentPicked, calc.coordinates());
                int removed = before - currentPicked.size();
                double removedRatio = before > 0 ? (double) removed / before : 0;
                if (removedRatio > 0.30) {
                    currentPicked.clear();
                    currentPicked.addAll(snapshot);
                    log.warn("DEDUP rollback: tried {} -> {} ({}% removed) zbyt agresywny -- koncze petle (lastGood ma stan z przed dedup)",
                            new Object[]{before, before - removed, Math.round(removedRatio * 100)});
                    break;
                }
                if (currentPicked.size() < before) {
                    currentWps = buildWaypointsFromPicked(prefs, sortByInsertionIdx(currentPicked),
                            coverageInfo.baselineGeometry());
                    log.info("DEDUP: {} -> {} picked (gminy pokryte naturalnie)",
                            new Object[]{before, currentPicked.size()});
                    continue;
                }
            }

            // Stan dobry, nic do dodania, nic do dedup — koniec.
            break;
        }

        // Po petli: preferuj lastGood (BEST stan -- najblizej growTargetKm). Jesli brak -- ostatni calc.
        if (lastGood != null) {
            if (lastGood.calc() != calc) {
                log.info("Returning lastGood: actualKm={} (vs last iter actualKm={}, growTarget={})",
                        new Object[]{Math.round(lastGood.calc().distanceKm()),
                                Math.round(calc.distanceKm()),
                                Math.round(growTargetKm)});
            }
            calc = lastGood.calc();
            currentWps = lastGood.finalWaypoints();
        }
        assertAnchorsReached(calc.coordinates(), prefs, maxGapKm);
        return new TraceResult(calc, currentWps);
    }

    /**
     * Konserwatywny grow: dodaje gminy z {@code reserve} (sorted by detour ASC) aż łączny
     * straight detour przekroczy {@code remainingStraightKm}. Bez tego limit grow potrafi
     * dorzucić 389 gmin (15% z 2592 reserve) co skutkuje przelaniem budżetu 3-4×.
     *
     * @return liczba dodanych gmin
     */
    private static int growUntilBudget(List<AreaCandidate> current, List<AreaCandidate> reserve,
                                       double remainingStraightKm) {
        if (reserve.isEmpty() || remainingStraightKm <= 0) return 0;
        var sorted = new ArrayList<>(reserve);
        sorted.sort((a, b) -> Double.compare(a.getDetourStraightKm(), b.getDetourStraightKm()));
        double used = 0;
        List<AreaCandidate> toAdd = new ArrayList<>();
        for (AreaCandidate c : sorted) {
            double cost = c.isIntersected() ? 0 : c.getDetourStraightKm();
            if (used + cost > remainingStraightKm) break;
            toAdd.add(c);
            used += cost;
        }
        current.addAll(toAdd);
        reserve.removeAll(toAdd);
        return toAdd.size();
    }

    /** Reserve = gminy które NIE weszły w greedy pick (były droższe niż dostępny budżet). */
    private static List<AreaCandidate> buildReservePoolFromUnpicked(CoverageBuildInfo info) {
        return new ArrayList<>(info.reserveCandidates());
    }

    /** Trim: wyrzuć `fraction` najdroższych (po detour DESC) z {@code from} do {@code dropped}. */
    private static void trimByDetourFromCurrent(List<AreaCandidate> from, List<AreaCandidate> dropped, double fraction) {
        if (from.isEmpty()) return;
        var sorted = new ArrayList<>(from);
        sorted.sort((a, b) -> Double.compare(b.getDetourStraightKm(), a.getDetourStraightKm()));
        int toDrop = Math.max(1, (int) Math.round(sorted.size() * fraction));
        toDrop = Math.min(toDrop, sorted.size());
        for (int i = 0; i < toDrop; i++) {
            AreaCandidate c = sorted.get(i);
            from.remove(c);
            dropped.add(c);
        }
    }

    /** Grow: weź `fraction` najtańszych (po detour ASC) z {@code reserve} → dodaj do {@code current}. */
    private static void growFromReserve(List<AreaCandidate> current, List<AreaCandidate> reserve, double fraction) {
        if (reserve.isEmpty()) return;
        var sorted = new ArrayList<>(reserve);
        sorted.sort((a, b) -> Double.compare(a.getDetourStraightKm(), b.getDetourStraightKm()));
        int toAdd = Math.max(1, (int) Math.round(reserve.size() * fraction));
        toAdd = Math.min(toAdd, sorted.size());
        for (int i = 0; i < toAdd; i++) {
            AreaCandidate c = sorted.get(i);
            current.add(c);
            reserve.remove(c);
        }
    }

    static List<AreaCandidate> sortByInsertionIdx(List<AreaCandidate> list) {
        var sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(a.getInsertionIdx(), b.getInsertionIdx()));
        return sorted;
    }

    /** Gap (km) od końcowego punktu geometrii do prefs.end (lub start dla loop). */
    private static double computeEndGap(List<double[]> geometry, RoutePreferences prefs) {
        if (geometry.isEmpty()) return Double.MAX_VALUE;
        Waypoint endWp = Boolean.TRUE.equals(prefs.loop()) ? prefs.start() : prefs.end();
        if (endWp == null) return 0;
        return WaypointSelector.haversineKm(geometry.get(geometry.size() - 1), endWp.toLngLat());
    }

    /** Wyrzuca {@code dropFraction} najdroższych gmin (po detourStraightKm DESC) i buduje nową listę waypointów. */
    static List<Waypoint> trimExpensiveGminy(RoutePreferences prefs, CoverageBuildInfo info, double dropFraction) {
        var picked = new ArrayList<>(info.pickedCandidates());
        // Sort po detourStraightKm DESC, weź top `dropFraction` → wyrzuć.
        picked.sort((a, b) -> Double.compare(b.getDetourStraightKm(), a.getDetourStraightKm()));
        int toDrop = (int) Math.round(picked.size() * dropFraction);
        toDrop = Math.min(toDrop, picked.size());
        List<AreaCandidate> kept = new ArrayList<>(picked.subList(toDrop, picked.size()));
        kept.sort((a, b) -> Integer.compare(a.getInsertionIdx(), b.getInsertionIdx()));
        return buildWaypointsFromPicked(prefs, kept, info.baselineGeometry());
    }

    /**
     * Po pierwszym BRouter sprawdza które entry-points gmin są ZBĘDNE — bo ich gmina jest naturalnie
     * przecięta przez nową trasę. Te entry-pointy wyrzucamy. Pozwala BRouter narysować trasę bez
     * pętelek wokół tej samej gminy z trzech stron.
     */
    /**
     * In-place: usuwa z {@code picked} kandydatów których ring jest INTERSECTED przez nową geometrię
     * (BRouter naturalnie przez nich przejeżdża → entry-point zbędny). Bez tego dedup tylko wycina
     * waypoints ale picked zachowuje wyrzucone → następny grow je przywraca.
     */
    static void removeNaturallyCoveredFromPicked(List<AreaCandidate> picked, List<double[]> newGeometry) {
        if (picked.isEmpty() || newGeometry.isEmpty()) return;
        List<AreaCandidate> toRemove = new ArrayList<>();
        for (AreaCandidate c : picked) {
            if (c.isIntersected()) continue;
            if (isAreaCoveredByGeometry(c.getArea(), newGeometry)) {
                toRemove.add(c);
            }
        }
        picked.removeAll(toRemove);
    }

    /**
     * Czy ring obszaru jest przeciety przez ktorykolwiek punkt {@code geometry} (point-in-ring
     * w oknie +-300 punktow wokol najblizszego do centroidu)? Reuzywane w {@code removeNaturallyCoveredFromPicked}
     * oraz w GROW pre-filter (zeby nie dodawac jako waypoint obszarow przez ktore slad juz idzie).
     */
    static boolean isAreaCoveredByGeometry(UnvisitedArea area, List<double[]> geometry) {
        if (area == null || geometry == null || geometry.isEmpty()) return false;
        double[][] ring = area.ring();
        if (ring == null || ring.length < 3) return false;
        int near = findNearestGeomIdx(geometry, new double[]{area.lng(), area.lat()});
        int from = Math.max(0, near - 300);
        int to = Math.min(geometry.size(), near + 300);
        for (int i = from; i < to; i++) {
            if (WaypointSelector.pointInRing(geometry.get(i), ring)) {
                return true;
            }
        }
        return false;
    }

    static List<Waypoint> removeNaturallyCoveredEntries(RoutePreferences prefs, CoverageBuildInfo info,
                                                        List<double[]> newGeometry, List<Waypoint> currentWps) {
        // Set entry-pointów które MOŻNA wyrzucić = entry-pointy gmin których ring jest intersected by newGeometry.
        Set<String> droppableEntryNames = new HashSet<>();
        for (AreaCandidate c : info.pickedCandidates()) {
            if (c.area.ring() == null || c.area.ring().length < 3) continue;
            // Sprawdź ±300 punktów wokół insertionIdx newGeometry — czy któryś leży w ringu.
            // newGeometry ma INNE indeksy niż baselineGeometry, więc szukamy najbliższego punktu.
            int near = findNearestGeomIdx(newGeometry, new double[]{c.area.lng(), c.area.lat()});
            int from = Math.max(0, near - 300);
            int to = Math.min(newGeometry.size(), near + 300);
            for (int i = from; i < to; i++) {
                if (WaypointSelector.pointInRing(newGeometry.get(i), c.area.ring())) {
                    droppableEntryNames.add(c.area.name() + "@" + c.getInsertionIdx());
                    break;
                }
            }
        }
        if (droppableEntryNames.isEmpty()) return currentWps;
        List<Waypoint> kept = new ArrayList<>(currentWps.size());
        int dropped = 0;
        // Iteruj currentWps; START + USER VIA + END muszą zostać. Gminy entry-pointy o nazwie
        // znajdującej się w droppableEntryNames → wyrzucamy (NIE wszystkie z tą nazwą — tylko tyle ile
        // jest na droppable set).
        Set<String> userAnchorNames = new HashSet<>();
        if (prefs.start() != null && prefs.start().name() != null) userAnchorNames.add(prefs.start().name());
        if (prefs.end() != null && prefs.end().name() != null) userAnchorNames.add(prefs.end().name());
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) {
                if (v.name() != null) userAnchorNames.add(v.name());
            }
        }
        for (Waypoint w : currentWps) {
            if (w.name() != null && !userAnchorNames.contains(w.name())) {
                // Gmina entry-point — sprawdź czy nazwa jest w droppable.
                // Konstrukcja klucza: "name@insertionIdx" wymaga mapowania. Uproszczenie: dropuj wszystkie
                // entry-pointy o nazwie znajdującej się w droppable (set zawiera "name@idx"), patrzymy
                // czy name pasuje do KTÓREJKOLWIEK klucza.
                boolean canDrop = false;
                for (String key : droppableEntryNames) {
                    if (key.startsWith(w.name() + "@")) {
                        canDrop = true;
                        break;
                    }
                }
                if (canDrop) {
                    dropped++;
                    continue;
                }
            }
            kept.add(w);
        }
        log.info("Dedup analysis: {} entry-points naturally covered, {} actually dropped from waypoints",
                new Object[]{droppableEntryNames.size(), dropped});
        return kept;
    }

    /** Buduje finalną listę waypointów [start, picked entry-points by insertionIdx, via, ..., end] z `picked`. */
    static List<Waypoint> buildWaypointsFromPicked(RoutePreferences prefs, List<AreaCandidate> picked,
                                                   List<double[]> baselineGeom) {
        boolean loop = Boolean.TRUE.equals(prefs.loop());
        List<Waypoint> anchorWps = new ArrayList<>();
        anchorWps.add(prefs.start());
        if (prefs.via() != null) anchorWps.addAll(prefs.via());
        if (loop) anchorWps.add(prefs.start());
        else if (prefs.end() != null) anchorWps.add(prefs.end());
        else anchorWps.add(prefs.start());

        int[] anchorIndices = new int[anchorWps.size()];
        for (int i = 0; i < anchorWps.size(); i++) {
            anchorIndices[i] = findNearestGeomIdx(baselineGeom, anchorWps.get(i).toLngLat());
        }
        List<Waypoint> result = new ArrayList<>();
        result.add(anchorWps.get(0));
        int pickedPtr = 0;
        for (int ai = 1; ai < anchorWps.size(); ai++) {
            int anchorIdx = anchorIndices[ai];
            while (pickedPtr < picked.size() && picked.get(pickedPtr).getInsertionIdx() <= anchorIdx) {
                AreaCandidate c = picked.get(pickedPtr);
                // Skip entry-point dla ZWYKLYCH gmin/powiatow z intersected=true (baseline przez ring).
                // BRouter naturalnie przejdzie -- dodatkowy waypoint powoduje objazd.
                // ALE: dla SPECIAL GROUPS (np. kreissitz = konkretne miasto-stolica) -- ZAWSZE
                // dodajemy entry-point. Special group requires precyzyjne przejscie przez centrum,
                // nie tylko musnięcie ringa. Bez tego: kreissitz Chemnitz intersected -> pominięty
                // -> BRouter idzie obok przez wioskę -> kreissitz NIE zaliczony.
                // Iter 9 Fix #1: skip mutually-covered (trasa BRouter naturalnie przejdzie przez ring sąsiada).
                if ((!c.isIntersected() || c.area.isSpecial()) && !c.isMutuallyCoveredByNeighbor()) {
                    result.add(new Waypoint(c.getEntryLng(), c.getEntryLat(), c.area.name()));
                }
                pickedPtr++;
            }
            result.add(anchorWps.get(ai));
        }
        while (pickedPtr < picked.size()) {
            AreaCandidate c = picked.get(pickedPtr);
            if (!c.isIntersected() || c.area.isSpecial()) {
                result.add(new Waypoint(c.getEntryLng(), c.getEntryLat(), c.area.name()));
            }
            pickedPtr++;
        }
        return result;
    }

    /**
     * Pre-screen baseline: BRouter na samych anchorach [start, via..., end] + elevation. Aktualizuje
     * {@code calibrator.applyAnchorsProbe}. Zwraca dystans/wznios/straight do dalszego użycia w
     * reconcile (extra = pełna trasa − baseline).
     */
    private BaselineProbe computeBaseline(RoutePreferences prefs, String profile, RoadFactorCalibrator calibrator) {
        List<double[]> anchors = new ArrayList<>();
        anchors.add(prefs.start().toLngLat());
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) anchors.add(v.toLngLat());
        }
        if (Boolean.TRUE.equals(prefs.loop())) {
            anchors.add(prefs.start().toLngLat());
        } else if (prefs.end() != null) {
            anchors.add(prefs.end().toLngLat());
        } else {
            anchors.add(prefs.start().toLngLat());
        }
        double straight = waypointSelector.straightLineDistanceKm(anchors);
        try {
            RouteCalculation r = routeUseCase.calculate(
                    new CalculateRouteUseCase.CalculateRouteCommand(anchors, profile, false));
            ElevationProfile elev = elevation.sample(r.coordinates());
            calibrator.applyAnchorsProbe(r.distanceKm(), straight);
            return new BaselineProbe(r.distanceKm(), elev.gainM(), straight, r.coordinates());
        } catch (RuntimeException e) {
            // Fallback: gdy BRouter na anchorach padnie (timeout, target-island), używamy straight × default factor.
            // Geometry = lista anchors (degenerated polyline) — algorytm snap-to-baseline będzie miał ograniczony filter.
            log.warn("Baseline BRouter probe failed ({}), falling back to straight × default", e.getMessage());
            calibrator.applyAnchorsFallback(RoadFactorCalibrator.LevelTier.REGION);
            return new BaselineProbe(straight * calibrator.roadAnchors(), 0, straight, new ArrayList<>(anchors));
        }
    }

    /**
     * Wzajemny dedup PRZED BRouter: jeśli entry-point gminy B leży w ringu gminy A (lub odwrotnie),
     * BRouter idąc do B i tak przejdzie przez A. Wyrzucamy A — zostaje tylko B. Plus: jeśli straight
     * line między dwoma SĄSIEDNIMI (po insertionIdx) entry-pointami przecina ring trzeciej gminy C
     * która leży POMIĘDZY — wyrzucamy C (BRouter pewnie przejdzie przez nią naturalnie).
     *
     * <p>Eliminuje user-widoczne pętelki "wjazd do gminy z 3 stron" przez REDUKCJĘ liczby entry-pointów
     * w ciasno upakowanym klastrze sąsiednich gmin.
     */
    static List<AreaCandidate> dedupByMutualCoverage(List<AreaCandidate> picked) {
        if (picked.size() < 2) return new ArrayList<>(picked);
        Set<Integer> toRemove = new HashSet<>();
        // Pass 1: entry-point sąsiada wewnątrz ringa.
        for (int i = 0; i < picked.size(); i++) {
            if (toRemove.contains(i)) continue;
            AreaCandidate ci = picked.get(i);
            double[][] ring = ci.getArea().ring();
            if (ring == null || ring.length < 3) continue;
            for (int j = 0; j < picked.size(); j++) {
                if (j == i || toRemove.contains(j)) continue;
                AreaCandidate cj = picked.get(j);
                double[] cjEntry = {cj.getEntryLng(), cj.getEntryLat()};
                if (WaypointSelector.pointInRing(cjEntry, ring)) {
                    // cj.entry ∈ ci.ring → BRouter idąc do cj i tak przejdzie przez ci.
                    // Preferencje wyboru ktore zachowac:
                    //   1) Zachowaj SPECIAL (kreissitz musi byc explicit -- centrum miasta, nie brzeg)
                    //   2) W razie remisu specjala -- zachowaj z mniejszym detourStraightKm
                    int toDrop;
                    boolean iSpecial = ci.area.isSpecial();
                    boolean jSpecial = cj.area.isSpecial();
                    if (iSpecial && !jSpecial) toDrop = j;
                    else if (!iSpecial && jSpecial) toDrop = i;
                    else toDrop = ci.getDetourStraightKm() > cj.getDetourStraightKm() ? i : j;
                    toRemove.add(toDrop);
                    if (toDrop == i) break;
                }
            }
        }
        // Pass 2: gmina k leżąca POMIĘDZY dwoma innymi (segm i→j przecina k.ring).
        // Sortuj NIE-usunięte po insertionIdx żeby sprawdzić sąsiednie pary.
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            if (!toRemove.contains(i)) remaining.add(i);
        }
        remaining.sort((a, b) -> Integer.compare(picked.get(a).getInsertionIdx(), picked.get(b).getInsertionIdx()));
        for (int r = 0; r < remaining.size() - 1; r++) {
            int idx1 = remaining.get(r);
            int idx2 = remaining.get(r + 1);
            AreaCandidate c1 = picked.get(idx1);
            AreaCandidate c2 = picked.get(idx2);
            double[] p1 = {c1.getEntryLng(), c1.getEntryLat()};
            double[] p2 = {c2.getEntryLng(), c2.getEntryLat()};
            // Czy któraś INNA pre-picked gmina ma ring przecięty przez segment p1-p2?
            for (int k = 0; k < picked.size(); k++) {
                if (k == idx1 || k == idx2 || toRemove.contains(k)) continue;
                AreaCandidate ck = picked.get(k);
                if (ck.isIntersected()) continue; // free → already counted
                double[][] ring = ck.getArea().ring();
                if (ring == null || ring.length < 3) continue;
                if (segmentIntersectsRing(p1, p2, ring)) {
                    toRemove.add(k);
                }
            }
        }
        // Iter 9 Fix #1: NIE usuwamy areas z listy. Zostają w `picked` (= raportowane jako
        // zaliczone), ale SETujemy flagę `mutuallyCoveredByNeighbor=true`. Tour builder SKIPS
        // tych przy generowaniu waypointów. User widzi je jako zaliczone (różowe gminy)
        // mimo że nie ma dla nich explicit waypoint w trasie. Pre-iter9: areas były wyrzucane
        // z picked → user widział je jako BIAŁE dziury.
        for (int idx : toRemove) {
            picked.get(idx).setMutuallyCoveredByNeighbor(true);
        }
        return picked;
    }

    /** Czy odcinek p1→p2 przecina poligon (ring)? Test: czy któryś sąsiedni ring vertex jest po przeciwnej stronie. */
    static boolean segmentIntersectsRing(double[] p1, double[] p2, double[][] ring) {
        // Najpierw szybkie odrzucenie po bbox.
        double minLng = Math.min(p1[0], p2[0]), maxLng = Math.max(p1[0], p2[0]);
        double minLat = Math.min(p1[1], p2[1]), maxLat = Math.max(p1[1], p2[1]);
        double ringMinLng = Double.MAX_VALUE, ringMaxLng = -Double.MAX_VALUE;
        double ringMinLat = Double.MAX_VALUE, ringMaxLat = -Double.MAX_VALUE;
        for (double[] r : ring) {
            if (r[0] < ringMinLng) ringMinLng = r[0];
            if (r[0] > ringMaxLng) ringMaxLng = r[0];
            if (r[1] < ringMinLat) ringMinLat = r[1];
            if (r[1] > ringMaxLat) ringMaxLat = r[1];
        }
        if (maxLng < ringMinLng || minLng > ringMaxLng || maxLat < ringMinLat || minLat > ringMaxLat) return false;
        // Endpoint test — szybko jeśli koniec w ringu.
        if (WaypointSelector.pointInRing(p1, ring) || WaypointSelector.pointInRing(p2, ring)) return true;
        // Test przecięcia z każdym bokiem ringa.
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            if (segmentsIntersect(p1, p2, ring[j], ring[i])) return true;
        }
        return false;
    }

    /** Standardowy test przecięcia dwóch odcinków a-b i c-d (2D). */
    private static boolean segmentsIntersect(double[] a, double[] b, double[] c, double[] d) {
        double d1 = cross(c, d, a);
        double d2 = cross(c, d, b);
        double d3 = cross(a, b, c);
        double d4 = cross(a, b, d);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        return false;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
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

    @SuppressWarnings("unused")
    private static String describeAnchors(List<Waypoint> ws) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) {
            if (i > 0) sb.append("→");
            sb.append(ws.get(i).name() != null ? ws.get(i).name() : ("pt" + i));
        }
        return sb.toString();
    }

    /** Wynik pętli budget reconcile (stary algorytm; w Fazie 4 zastąpiony przez snap-to-baseline). */
    @SuppressWarnings("unused")
    private record BudgetReconcileResult(
            List<UnvisitedArea> finalOrder, double estKm,
            BudgetReconciler.Verdict verdict, int iters, int trims, int grows) {
    }

    /**
     * STARY algorytm — bisekcja po pool size + TSP per segment. Zastąpiony w Fazie 4 przez
     * snap-to-baseline (BRouter start→via→meta JEST dojazdem, gminy dolinkowane mini-detorami
     * na granicę polygonu — patrz {@link #scoreAreaAgainstBaseline}). Zachowane na wypadek
     * gdyby snap-to-baseline okazał się gorszy w testach.
     */
    @SuppressWarnings("unused")
    private BudgetReconcileResult reconcileBudget(UUID taskId, List<UnvisitedArea> working,
                                                  List<UnvisitedArea> reserve,
                                                  RoutePreferences prefs, RoadFactorCalibrator calibrator,
                                                  String brouterProfile,
                                                  double[] start, double[] end,
                                                  BaselineProbe baseline) {
        List<UnvisitedArea> allRanked = new ArrayList<>(working.size() + reserve.size());
        allRanked.addAll(working);
        allRanked.addAll(reserve);
        int totalCandidates = allRanked.size();

        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : 0;
        double upperBound = budgetKm * (1 + BUDGET_TOLERANCE);  // 1.05 × budget
        double lowerBound = budgetKm * (1 - BUDGET_TOLERANCE);  // 0.95 × budget

        int low = 0;                          // ostatni rozmiar który zmieścił się (SURPLUS/OK)
        int high = totalCandidates + 1;       // pierwszy rozmiar który przekroczył (DEFICIT)
        int trims = 0, grows = 0;

        // Najlepszy znaleziony do tej pory: największa pula z verdict != DEFICIT.
        int bestSize = -1;
        List<UnvisitedArea> bestOrdered = null;
        double bestEstKm = 0;
        BudgetReconciler.Verdict bestVerdict = BudgetReconciler.Verdict.OK;

        int candidateSize = Math.min(working.size(), totalCandidates);
        int maxIters = MAX_BUDGET_TRIM + MAX_BUDGET_GROW;
        int it = 0;
        for (; it < maxIters; it++) {
            checkCancel(taskId);
            candidateSize = Math.max(2, Math.min(candidateSize, totalCandidates));
            List<UnvisitedArea> candidate = new ArrayList<>(allRanked.subList(0, candidateSize));
            List<UnvisitedArea> ordered = waypointSelector.orderAreas(candidate, start, end,
                    () -> progressSink != null && progressSink.isCancelRequested(taskId));
            List<double[]> orderedPts = buildOrderedPoints(prefs, ordered, start, end);
            // Estymata po nowemu: extraStraight = full − anchorsStraight; extraReal = extraStraight × roadAreas;
            // totalDistEst = baseline.distanceKm + extraReal. Verdict TYLKO po km, climb to inna oś.
            double straightFull = waypointSelector.straightLineDistanceKm(orderedPts);
            double extraStraight = Math.max(0, straightFull - baseline.anchorsStraightKm);
            double extraReal = extraStraight * calibrator.roadAreas();
            double totalDistEst = baseline.distanceKm + extraReal;

            var result = BudgetReconciler.evaluate(totalDistEst, prefs.days(), prefs.kmPerDay());
            log.info("Reconcile iter={} pool={} (range [{},{}]) straightFull={} extraStraight={} extraReal={} totalDist={} budget={} verdict={}",
                    new Object[]{it, candidateSize, low,
                            high == totalCandidates + 1 ? "∞" : String.valueOf(high),
                            Math.round(straightFull), Math.round(extraStraight),
                            Math.round(extraReal), Math.round(totalDistEst),
                            result.budgetKm(), result.verdict()});

            // Preferujemy: największa pula z verdict != DEFICIT (mieści się w budżecie).
            boolean fitsInBudget = result.verdict() != BudgetReconciler.Verdict.DEFICIT;
            boolean bestFitsInBudget = bestOrdered != null && bestVerdict != BudgetReconciler.Verdict.DEFICIT;
            if (bestOrdered == null
                    || (fitsInBudget && (!bestFitsInBudget || candidateSize > bestSize))
                    || (!fitsInBudget && !bestFitsInBudget && totalDistEst < bestEstKm)) {
                bestSize = candidateSize;
                bestOrdered = ordered;
                bestEstKm = totalDistEst;
                bestVerdict = result.verdict();
            }

            // Konwergencja: estKm w paśmie OK → kończymy z tym rozmiarem.
            if (totalDistEst >= lowerBound && totalDistEst <= upperBound) {
                bestSize = candidateSize;
                bestOrdered = ordered;
                bestEstKm = totalDistEst;
                bestVerdict = result.verdict();
                log.info("Reconcile converged at iter={} pool={} (budget {} ±5%)", it, candidateSize, budgetKm);
                break;
            }

            // Bisekcja po pool size:
            int nextSize;
            if (totalDistEst > upperBound) {
                high = candidateSize;
                trims++;
                int floor = Math.max(2, low + 1);
                nextSize = (floor + high) / 2;
                if (nextSize >= candidateSize) nextSize = candidateSize - 1;
            } else {
                low = candidateSize;
                grows++;
                if (candidateSize >= totalCandidates) {
                    log.info("Reconcile: max pool exhausted ({}), can't grow further", totalCandidates);
                    break;
                }
                int ceil = (high == totalCandidates + 1) ? totalCandidates : high;
                nextSize = (low + ceil) / 2;
                if (nextSize <= candidateSize) nextSize = candidateSize + 1;
            }
            if ((high - low) <= 1) {
                log.info("Reconcile: bisection range collapsed [{}, {}], best size={}", new Object[]{low, high, bestSize});
                break;
            }
            candidateSize = nextSize;
        }
        return new BudgetReconcileResult(
                bestOrdered != null ? bestOrdered : working,
                bestEstKm, bestVerdict, it, trims, grows);
    }

    /** Buduje PlanningSummary z policzonych dni + diagnostyki reconcile (gdy COVERAGE). */
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
        int iters       = coverageInfo != null ? coverageInfo.reconcileIters()  : 0;
        int trims       = coverageInfo != null ? coverageInfo.reconcileTrims()  : 0;
        int grows       = coverageInfo != null ? coverageInfo.reconcileGrows()  : 0;
        Double baselineKm = coverageInfo != null ? coverageInfo.baselineKm() : null;
        Double roadAnchors = coverageInfo != null ? coverageInfo.roadAnchors() : null;
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
                finalPool, initialPool, iters, trims, grows,
                baselineKm, roadAnchors, roadAreas, climbWarning);
    }

    /** Suma haversinów anchor-to-anchor (start→via[*]→end), bez obszarów pomiędzy. */
    private double computeAnchorsStraight(List<Waypoint> allWaypoints) {
        if (allWaypoints == null || allWaypoints.size() < 2) return 0;
        // Anchors to PIERWSZY i OSTATNI element + via points (które są oryginalnie z prefs).
        // W allWaypoints są też centroidy obszarów — nie wiemy które bez doinformowania.
        // Najprościej: pierwszy + ostatni (ignorując via). Akceptowalne przybliżenie dla diagnostyki EMA.
        double[] first = allWaypoints.get(0).toLngLat();
        double[] last = allWaypoints.get(allWaypoints.size() - 1).toLngLat();
        return WaypointSelector.haversineKm(first, last);
    }

    /**
     * Weryfikuje że BRouter rzeczywiście dotarł do każdego USER-DEFINED anchor'a (start, prefs.via[*], end).
     * NIE sprawdza gmin entry-pointów — one są naszymi heurystycznymi „checkpointami" do zaliczenia
     * gminy, nie obowiązkowymi punktami trasy. BRouter może je legalnie pomijać o ~kilkaset metrów.
     *
     * <p>Anchors są wyłuskiwane bezpośrednio z {@code prefs} — niezależne od kolejności w allWaypoints.
     */
    private void assertAnchorsReached(List<double[]> geometry, RoutePreferences prefs, double maxGapKm) {
        if (geometry.isEmpty() || prefs.start() == null) return;
        double maxGap = 0;
        String worstAnchor = null;
        // START
        double startGap = WaypointSelector.haversineKm(geometry.get(0), prefs.start().toLngLat());
        if (startGap > maxGap) { maxGap = startGap; worstAnchor = "start:" + (prefs.start().name() != null ? prefs.start().name() : "?"); }
        // END (z uwzględnieniem loop)
        Waypoint endWp = Boolean.TRUE.equals(prefs.loop()) ? prefs.start() : prefs.end();
        if (endWp != null) {
            double endGap = WaypointSelector.haversineKm(
                    geometry.get(geometry.size() - 1), endWp.toLngLat());
            if (endGap > maxGap) { maxGap = endGap; worstAnchor = "end:" + (endWp.name() != null ? endWp.name() : "?"); }
        }
        // VIA — szukamy najbliższego punktu geometrii dla każdego prefs.via[i].
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) {
                double[] target = v.toLngLat();
                double minGap = Double.MAX_VALUE;
                for (double[] g : geometry) {
                    double d = WaypointSelector.haversineKm(g, target);
                    if (d < minGap) {
                        minGap = d;
                        if (minGap <= 0.5) break;
                    }
                }
                if (minGap > maxGap) {
                    maxGap = minGap;
                    worstAnchor = "via:" + (v.name() != null ? v.name() : "?");
                }
            }
        }
        log.info("Reachability (user anchors only): max gap {} km (worst: {})",
                new Object[]{String.format("%.2f", maxGap), worstAnchor});
        if (maxGap > maxGapKm) {
            throw new PlanningSessionNotReadyException(
                    "BRouter nie dotarł do " + worstAnchor + " (gap " + String.format("%.1f", maxGap) + " km). " +
                    "Najprawdopodobniej target-island albo zbyt wiele gmin do dolinkowania. " +
                    "Spróbuj przesunąć ten punkt o ~1 km do najbliższej drogi lub zwiększ budżet (km/dzień).");
        }
    }

    /** Zbiera punkty w tej samej kolejności co `buildCoverageWaypoints` zwróci: start + ordered + end/loop. */
    private static List<double[]> buildOrderedPoints(RoutePreferences prefs, List<UnvisitedArea> ordered,
                                                    double[] start, double[] end) {
        boolean loop = Boolean.TRUE.equals(prefs.loop());
        List<double[]> pts = new ArrayList<>(ordered.size() + 2);
        pts.add(start);
        for (UnvisitedArea a : ordered) pts.add(new double[]{a.lng(), a.lat()});
        if (loop) pts.add(start);
        else if (end != null) pts.add(end);
        return pts;
    }

    /**
     * Dzieli {@code waypoints} na chunki ≤ {@link #MAX_WAYPOINTS_PER_DAY} z 1-punktowym overlapem
     * (koniec chunka N = początek chunka N+1) i klei geometry/distance/duration w jeden wynik.
     * Bez tego BRouter zwraca 414 Request-URI Too Large przy >~50 lonlatach.
     */
    RouteCalculation calculateRouteChunked(UUID taskId, List<double[]> waypoints, String profile) {
        return calculateRouteChunked(taskId, waypoints, profile, true);
    }

    /**
     * @param computeStats {@code true} = agreguj per-chunk stats + loguj agregat całej trasy.
     *        {@code false} = skip stats wszędzie (per-chunk routeUseCase.calculate też dostaje false)
     *        — używane przez ALNS2/TSP/ALNS3 dla intermediate probing (~10k+ calls per coverage plan)
     *        gdzie stats nie są używane a logi zalewały konsolę.
     */
    RouteCalculation calculateRouteChunked(UUID taskId, List<double[]> waypoints, String profile, boolean computeStats) {
        // GLOBALNY cache bad waypoints (target-island). Thread-safe bo chunki liczone RÓWNOLEGLE.
        java.util.Set<String> badCoordsCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

        if (waypoints.size() <= MAX_WAYPOINTS_PER_DAY) {
            return calculateChunkResilient(taskId, waypoints, profile, badCoordsCache, computeStats);
        }
        // Granice chunków (≤MAX_WAYPOINTS_PER_DAY, 1-pkt overlap: koniec N = początek N+1).
        List<List<double[]>> chunks = new ArrayList<>();
        int start = 0;
        while (start < waypoints.size() - 1) {
            int end = Math.min(start + MAX_WAYPOINTS_PER_DAY, waypoints.size());
            chunks.add(new ArrayList<>(waypoints.subList(start, end)));
            start = end - 1;
        }
        checkCancel(taskId);
        // Chunki są NIEZALEŻNE (rozłączne waypointy poza 1-pkt overlapem) → licz RÓWNOLEGLE.
        // ALE zbuj współbieżność LOKALNYM semaforem (≤ MAX_PARALLEL_CHUNKS), inaczej np. 42 chunki
        // (cała Polska) wrzucone naraz przepełniają Semaphore(max-concurrent=10, wait 5s) w
        // HttpBrouterRoutingClient → ogon czeka >5s → BrouterUnavailable (429). Lokalny gate nie ma
        // timeoutu, więc tylko bounduje, nie odrzuca. Pre-filtr badCoordsCache pominięty (cache pusty).
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(MAX_PARALLEL_CHUNKS);
        List<RouteCalculation> results = new ArrayList<>(java.util.Collections.nCopies(chunks.size(), null));
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                final int idx = i;
                futures.add(exec.submit(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        results.set(idx, calculateChunkResilient(taskId, chunks.get(idx), profile, badCoordsCache, computeStats));
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during parallel chunked BRouter", e);
                }
            }
        }
        checkCancel(taskId);
        // Sklejanie W KOLEJNOŚCI chunków, pomijając zduplikowany punkt overlapu.
        // Stats: meters agregujemy przez accumulator + spans łączymy z offsetem per chunk
        // (indeksy w spans CHUNK'a są lokalne; po sklejaniu mergedCoords muszą być globalne).
        List<double[]> mergedCoords = new ArrayList<>();
        double totalDistKm = 0;
        velomarker.service.RouteStatsAccumulator statsAcc = new velomarker.service.RouteStatsAccumulator();
        List<velomarker.entity.RouteSpan> mergedSurfaceSpans = new ArrayList<>();
        List<velomarker.entity.RouteSpan> mergedRoadSpans = new ArrayList<>();
        List<velomarker.entity.RouteSpan> mergedSmoothnessSpans = new ArrayList<>();
        for (RouteCalculation r : results) {
            int offsetIdx = mergedCoords.size() == 0 ? 0 : mergedCoords.size() - 1; // -1 bo następny chunk pomija pierwszy overlap waypoint
            int skipFirst = mergedCoords.isEmpty() ? 0 : 1;
            if (mergedCoords.isEmpty()) {
                mergedCoords.addAll(r.coordinates());
            } else {
                mergedCoords.addAll(r.coordinates().subList(1, r.coordinates().size()));
            }
            totalDistKm += r.distanceKm();
            statsAcc.add(r.stats());

            velomarker.entity.RouteStats chunkStats = r.stats();
            if (chunkStats != null) {
                int baseOffset = offsetIdx;
                int firstSkip = skipFirst;
                appendSpansWithOffset(mergedSurfaceSpans, chunkStats.surfaceSpans(), baseOffset, firstSkip);
                appendSpansWithOffset(mergedRoadSpans, chunkStats.roadSpans(), baseOffset, firstSkip);
                appendSpansWithOffset(mergedSmoothnessSpans, chunkStats.smoothnessSpans(), baseOffset, firstSkip);
            }
        }
        velomarker.entity.RouteStats aggregatedMaps = statsAcc.build();
        velomarker.entity.RouteStats aggregatedStats = new velomarker.entity.RouteStats(
                aggregatedMaps.totalMeters(),
                aggregatedMaps.surfaceMeters(),
                aggregatedMaps.roadMeters(),
                aggregatedMaps.smoothnessMeters(),
                mergedSurfaceSpans,
                mergedRoadSpans,
                mergedSmoothnessSpans);
        if (computeStats) {
            log.info("BRouter chunked (parallel): {} waypoints → {} chunks → {} coords total (badCache size={})",
                    new Object[]{waypoints.size(), chunks.size(), mergedCoords.size(), badCoordsCache.size()});
            // Debug: ile stats z chunks faktycznie dotarło
            int chunksWithStats = 0;
            int chunksWithSpans = 0;
            for (RouteCalculation r : results) {
                if (r.stats() != null && r.stats().totalMeters() > 0) chunksWithStats++;
                if (r.stats() != null && !r.stats().surfaceSpans().isEmpty()) chunksWithSpans++;
            }
            log.info("Chunks stats debug: {} chunks total, {} z totalMeters>0, {} z surfaceSpans niepuste. Aggregated spans: surface={} road={} smoothness={}, surfaceMeters keys={}, roadMeters keys={}",
                    new Object[]{results.size(), chunksWithStats, chunksWithSpans,
                            mergedSurfaceSpans.size(), mergedRoadSpans.size(), mergedSmoothnessSpans.size(),
                            aggregatedMaps.surfaceMeters().size(), aggregatedMaps.roadMeters().size()});
            log.info(velomarker.service.RouteStatsFormatter.format(aggregatedStats,
                    "Statystyki całej trasy (chunked, profil: " + profile + ")"));
        }
        return new RouteCalculation(mergedCoords, totalDistKm, java.util.List.of(), aggregatedStats);
    }

    /**
     * Przesuwa spans z lokalnych indeksów chunku do globalnych (mergedCoords). {@code baseOffset}
     * to liczba wierzchołków już sklejonych PRZED tym chunkiem; {@code skipFirst} = 1 dla chunków
     * po pierwszym (overlap waypoint pomijany). Span [a, b] chunku → [baseOffset + a - skipFirst,
     * baseOffset + b - skipFirst]. Pierwszy chunk: skipFirst=0, baseOffset=0.
     */
    private static void appendSpansWithOffset(List<velomarker.entity.RouteSpan> out,
                                              List<velomarker.entity.RouteSpan> chunkSpans,
                                              int baseOffset, int skipFirst) {
        if (chunkSpans == null || chunkSpans.isEmpty()) return;
        for (velomarker.entity.RouteSpan sp : chunkSpans) {
            int s = sp.startIdx() - skipFirst;
            int e = sp.endIdx() - skipFirst;
            if (e < 0) continue; // span całkowicie w pomijanym pierwszym wierzchołku
            if (s < 0) s = 0;
            out.add(new velomarker.entity.RouteSpan(baseOffset + s, baseOffset + e, sp.code()));
        }
    }

    /** Klucz coords do cache (6 decimal precision = ~10cm; chunkuje float arytmetykę). */
    private static String coordKey(double[] coord) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", coord[0], coord[1]);
    }



    private static final java.util.regex.Pattern TARGET_ISLAND_PATTERN =
            java.util.regex.Pattern.compile("target island detected for section (\\d+)");
    // = rozmiar chunka: pozwala wydropować CAŁY klaster wysp w chunku (np. 9+ nieroutowalnych
    // waypointów nad Odrą) zamiast wywalać cały finalny/reconcile call. Limit 8 był za mały →
    // "za często się sypie" + fallback do prostych linii. Retry bounduje realna liczba wysp.
    private static final int MAX_ISLAND_RETRIES = MAX_WAYPOINTS_PER_DAY;

    /**
     * Wywołuje BRouter z retry: gdy zwróci 400 "target island detected for section N", usuwa
     * islanded waypoint i ponawia. Po MAX_ISLAND_RETRIES propaguje wyjątek.
     *
     * <p><b>Indeksacja (wg źródła BRoutera, RoutingEngine.tryFindTrack):</b> dla "section i"
     * target-island-check robi {@code findTrack(matchedWaypoints[i+1] → matchedWaypoints[i])} i rzuca
     * gdy {@code seg==null && nodeLimit>0} — czyli gdy {@code matchedWaypoints[i+1]} siedzi w MAŁYM
     * (&lt; MAXNODES_ISLAND_CHECK=500) odciętym komponencie drogowym (floodplain/wysepka/ślepy stub),
     * a NIE gdy brak dalekiego mostu (wtedy nodeLimit→0, brak wyjątku). {@code matchedWaypoints[0]}=start,
     * więc indeksy 1:1 z naszą listą → <b>wyspą jest {@code current[i+1]}, NIE {@code current[i]}</b>.
     * Usuwanie {@code i} (dawny bug) zostawiało wyspę i kasowało dobry punkt PRZED nią → kaskada
     * #N→…→#1 mordowała cały pas (pocket nad Wisłą). Usuwamy {@code i+1} = jedno celne usunięcie.
     *
     * <p>Iter 8: {@code badCoordsCache} — wspólna blacklist między chunkami. Każdy removed waypoint
     * trafia tutaj → kolejny chunk pre-filtruje go BEZ retry.
     */
    private RouteCalculation calculateChunkWithIslandRetry(UUID taskId, List<double[]> waypoints, String profile,
                                                            java.util.Set<String> badCoordsCache) {
        return calculateChunkWithIslandRetry(taskId, waypoints, profile, badCoordsCache, true);
    }

    private RouteCalculation calculateChunkWithIslandRetry(UUID taskId, List<double[]> waypoints, String profile,
                                                            java.util.Set<String> badCoordsCache, boolean computeStats) {
        List<double[]> current = new ArrayList<>(waypoints);
        int removedCount = 0;
        for (int attempt = 0; attempt <= MAX_ISLAND_RETRIES; attempt++) {
            checkCancel(taskId);
            try {
                return routeUseCase.calculate(new CalculateRouteUseCase.CalculateRouteCommand(current, profile, computeStats));
            } catch (RuntimeException ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                var matcher = TARGET_ISLAND_PATTERN.matcher(msg);
                if (!matcher.find()) {
                    throw ex; // inny błąd — nie maskujemy
                }
                int section = Integer.parseInt(matcher.group(1));
                // Wyspa = target segmentu i→i+1 = current[i+1] (patrz javadoc / źródło BRoutera).
                int badIdx = Math.max(1, Math.min(section + 1, current.size() - 1));
                if (current.size() <= 2) {
                    throw ex; // za mało punktów żeby coś usuwać
                }
                double[] removed = current.remove(badIdx);
                removedCount++;
                // Iter 8: dodaj do globalnego cache. Kolejny chunk go pre-filtruje przed retry.
                if (badCoordsCache != null) {
                    badCoordsCache.add(coordKey(removed));
                }
                log.warn("BRouter target-island: removed waypoint #{} [{}, {}] (section {}), retry {}/{} [blacklist size={}]",
                        new Object[]{badIdx, removed[0], removed[1], section, attempt + 1, MAX_ISLAND_RETRIES,
                                badCoordsCache != null ? badCoordsCache.size() : 0});
            }
        }
        log.error("BRouter target-island: max retries ({}) exhausted after removing {} waypoints",
                MAX_ISLAND_RETRIES, removedCount);
        return routeUseCase.calculate(new CalculateRouteUseCase.CalculateRouteCommand(current, profile, computeStats));
    }

    /** Convenience overload bez cache — dla wywołań nie-chunked (probe, baseline). */
    private RouteCalculation calculateChunkWithIslandRetry(UUID taskId, List<double[]> waypoints, String profile) {
        return calculateChunkWithIslandRetry(taskId, waypoints, profile, new java.util.HashSet<>());
    }

    /**
     * Liczy chunk po DROGACH, odpornie na timeout/upstream BRoutera — NIGDY prostą linią (user: „chcę
     * drogowe trasy"). Na błąd: 1 retry (przejściowe nasycenie BRoutera), a gdy dalej pada — TNIE chunk
     * na pół (krótsze zapytania = szybszy BRouter, dalej drogowe) i skleja rekurencyjnie. Chunk ≤3 wp,
     * który mimo to nie wyrabia, propaguje wyjątek (po bumpie timeoutu to rzadkość — lepiej zgłosić niż
     * narysować prostą). TaskCancellation przechodzi (anuluj = anuluj).
     */
    private RouteCalculation calculateChunkResilient(UUID taskId, List<double[]> chunk, String profile,
                                                     java.util.Set<String> badCoordsCache) {
        return calculateChunkResilient(taskId, chunk, profile, badCoordsCache, true);
    }

    private RouteCalculation calculateChunkResilient(UUID taskId, List<double[]> chunk, String profile,
                                                     java.util.Set<String> badCoordsCache, boolean computeStats) {
        try {
            return calculateChunkWithIslandRetry(taskId, chunk, profile, badCoordsCache, computeStats);
        } catch (TaskCancellationException ce) {
            throw ce;
        } catch (velomarker.exception.BrouterMissingTileException mte) {
            recordMissingTile(taskId, mte);   // brak tile = retry/split bezsensowny (ten sam region)
            throw mte;
        } catch (RuntimeException first) {
            try {
                return calculateChunkWithIslandRetry(taskId, chunk, profile, badCoordsCache, computeStats); // 1 retry
            } catch (TaskCancellationException ce) {
                throw ce;
            } catch (velomarker.exception.BrouterMissingTileException mte) {
                recordMissingTile(taskId, mte);
                throw mte;
            } catch (RuntimeException second) {
                if (chunk.size() <= 3) {
                    throw second; // za mały by dzielić — propaguj (rzadkie po bumpie timeoutu)
                }
                int mid = chunk.size() / 2;
                log.warn("BRouter chunk {} wp nie wyrobił ({}) → tnę na pół i routuję po drogach",
                        new Object[]{chunk.size(), second.getMessage()});
                RouteCalculation left = calculateChunkResilient(taskId,
                        new java.util.ArrayList<>(chunk.subList(0, mid + 1)), profile, badCoordsCache, computeStats);
                RouteCalculation right = calculateChunkResilient(taskId,
                        new java.util.ArrayList<>(chunk.subList(mid, chunk.size())), profile, badCoordsCache, computeStats);
                List<double[]> coords = new java.util.ArrayList<>(left.coordinates());
                if (!right.coordinates().isEmpty()) {
                    coords.addAll(right.coordinates().subList(1, right.coordinates().size())); // pomiń overlap
                }
                return new RouteCalculation(coords, left.distanceKm() + right.distanceKm());
            }
        }
    }

    private void recordMissingTile(UUID taskId, velomarker.exception.BrouterMissingTileException ex) {
        java.util.Set<String> sink = missingTilesPerTask.get(taskId);
        if (sink != null) sink.add(ex.tileName());
    }

    /** Probe BRouter dla pierwszego odcinka (AB/FREESTYLE). Failed probe → keep fallback. */
    private void calibrateRoadFactor(List<Waypoint> waypoints, String profile, RoadFactorCalibrator calibrator) {
        if (waypoints.size() < 2) return;
        try {
            double[] a = waypoints.get(0).toLngLat();
            double[] b = waypoints.get(waypoints.size() - 1).toLngLat();
            double straight = WaypointSelector.haversineKm(a, b);
            if (straight < 1.0) return;
            RouteCalculation probe = routeUseCase.calculate(
                    new CalculateRouteUseCase.CalculateRouteCommand(List.of(a, b), profile, false));
            calibrator.applyAnchorsProbe(probe.distanceKm(), straight);
        } catch (RuntimeException e) {
            log.warn("Road factor probe failed ({}) — keeping fallback {}", e.getMessage(), calibrator.roadAnchors());
        }
    }

    /** Haversine od punktu do odcinka start→end (przybliżenie planarne; ok dla wybierania kandydatów). */
    private static double distanceToSegmentKm(double[] p, double[] a, double[] b) {
        double ax = a[0], ay = a[1];
        double bx = b[0], by = b[1];
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0] - ax) * dx + (p[1] - ay) * dy) / len2));
        double[] proj = new double[]{ax + t * dx, ay + t * dy};
        return WaypointSelector.haversineKm(p, proj);
    }

    private RoadFactorCalibrator.LevelTier estimateLevelTier(List<UnvisitedArea> pool) {
        // Najwyższy poziom = najmniejszy levelOrder. W praktyce: estymata po średniej powierzchni obszaru.
        double avgKm2 = pool.stream().mapToDouble(UnvisitedArea::areaKm2).average().orElse(100);
        if (avgKm2 < 250) return RoadFactorCalibrator.LevelTier.MUNICIPALITY;
        if (avgKm2 < 2000) return RoadFactorCalibrator.LevelTier.DISTRICT;
        if (avgKm2 < 30000) return RoadFactorCalibrator.LevelTier.REGION;
        return RoadFactorCalibrator.LevelTier.COUNTRY;
    }

    private String resolveProfile(RoutePreferences prefs) {
        if (prefs.profile() != null && !prefs.profile().isBlank()) {
            return prefs.profile();
        }
        RouteStyle style = prefs.style();
        Tempo tempo = prefs.tempo();
        return profileMapper.toBrouterProfile(style, tempo);
    }

    /**
     * Mapowanie waypoint-planera → indeks w pełnej geometrii BRoutera. BRouter **SNAPUJE** waypointy
     * do najbliższego punktu drogi (offset 5-50 m), więc dokładny hash-match nie działa. Poprzednia
     * iteracja z 2-pointer'em + bounded window kaskadowo failowała: gdy pierwszy wp nie trafił w
     * okno, j stało, kolejne wp też failowały (stąd 8/348).
     *
     * <p>Teraz: {@link SpatialGrid} nad fullCoords → per wp `nearestIndexTo(wp.lng, wp.lat)` = O(1)
     * per query. Tolerancja 500m → wp z dystansem &gt; 500m oznacza prawdziwy chunk-fail.
     */
    private static int[] mapWaypointsToFullIndices(List<Waypoint> wps, List<double[]> fullCoords) {
        int n = fullCoords.size();
        int m = wps.size();
        int[] map = new int[m];
        if (m == 0 || n == 0) return map;
        double[][] pts = new double[n][];
        for (int k = 0; k < n; k++) pts[k] = fullCoords.get(k);
        SpatialGrid grid = new SpatialGrid(pts);
        final double TOL_KM = 0.5; // 500 m — BRouter snap typowo &lt; 50m, próg generosa
        for (int i = 0; i < m; i++) {
            Waypoint wp = wps.get(i);
            int idx = grid.nearestIndexTo(wp.lng(), wp.lat());
            if (idx < 0) {
                map[i] = -1;
                continue;
            }
            double distKm = grid.distKmFromExternal(idx, wp.lng(), wp.lat());
            map[i] = distKm <= TOL_KM ? idx : -1;
        }
        return map;
    }

    /** Per dzień: bierze entry-pointy plannera ({@code allWaypoints}) wpadające w zakres dnia
     *  ({@code [fullStartIdx, fullEndIdx]}). Dla mode AB (brak gmin — `allWaypoints.size() < 5`)
     *  fallback do evenly-spaced {@link #pickDayKnots}. Granice dnia: jeśli pierwszy/ostatni
     *  waypoint NIE jest dokładnie na granicy dnia, dorzuca kotwicę z {@code dayGeometry}. */
    private List<Waypoint> dayWaypointsFromPlanning(List<Waypoint> allWaypoints, int[] wpToFullIdx,
                                                     int fullStartIdx, int fullEndIdx,
                                                     List<double[]> fullCoords, List<double[]> dayGeometry) {
        if (allWaypoints.size() < 5) {
            return pickDayKnots(dayGeometry); // AB / brak gmin
        }
        // ANCHOR-PROXIMITY: jeśli któryś wp mapuje się DOKŁADNIE na fullStartIdx/fullEndIdx
        // (próg ±3 coords → toleruje snap BRoutera w okolicy granicy dnia), to ten wp pełni rolę kotwicy.
        // Inaczej dorzucimy syntetyczną Waypoint(startCoord/endCoord). Bez tej proximity-tolerancji
        // równość po lng/lat dawała duplikaty (planner wp + snapped coord = różne wartości).
        final int BOUNDARY_TOL_COORDS = 3;
        List<Waypoint> dayWps = new ArrayList<>();
        boolean hasStartAnchor = false;
        boolean hasEndAnchor = false;
        for (int k = 0; k < allWaypoints.size(); k++) {
            int idx = wpToFullIdx[k];
            if (idx < 0) continue;
            if (idx >= fullStartIdx && idx <= fullEndIdx) {
                dayWps.add(allWaypoints.get(k));
                if (idx <= fullStartIdx + BOUNDARY_TOL_COORDS) hasStartAnchor = true;
                if (idx >= fullEndIdx - BOUNDARY_TOL_COORDS) hasEndAnchor = true;
            }
        }
        if (!hasStartAnchor) {
            double[] startCoord = fullCoords.get(fullStartIdx);
            dayWps.add(0, new Waypoint(startCoord[0], startCoord[1], null));
        }
        if (!hasEndAnchor) {
            double[] endCoord = fullCoords.get(fullEndIdx);
            dayWps.add(new Waypoint(endCoord[0], endCoord[1], null));
        }
        return dayWps;
    }

    /** „Kotwice" dla edycji dnia — co N-ty punkt geometrii, max MAX_WAYPOINTS_PER_DAY. */
    private List<Waypoint> pickDayKnots(List<double[]> geometry) {
        if (geometry.isEmpty()) return List.of();
        int total = geometry.size();
        int target = Math.min(MAX_WAYPOINTS_PER_DAY, Math.max(2, total / 50));
        List<Waypoint> result = new ArrayList<>(target);
        int step = Math.max(1, total / target);
        for (int i = 0; i < total; i += step) {
            double[] p = geometry.get(i);
            result.add(new Waypoint(p[0], p[1], null));
        }
        double[] last = geometry.get(total - 1);
        Waypoint lastWp = new Waypoint(last[0], last[1], null);
        if (result.isEmpty() || !sameLngLat(result.get(result.size() - 1), lastWp)) {
            result.add(lastWp);
        }
        return result;
    }

    private static boolean sameLngLat(Waypoint a, Waypoint b) {
        return Math.abs(a.lng() - b.lng()) < 1e-9 && Math.abs(a.lat() - b.lat()) < 1e-9;
    }

    private static <T> List<T> thinTo(List<T> list, int target) {
        if (list.size() <= target) return list;
        List<T> out = new ArrayList<>(target);
        double step = (double) list.size() / target;
        out.add(list.get(0));
        for (int i = 1; i < target - 1; i++) {
            int idx = (int) Math.round(i * step);
            if (idx >= list.size()) idx = list.size() - 1;
            out.add(list.get(idx));
        }
        out.add(list.get(list.size() - 1));
        return out;
    }

    /** Cumulative dystans w km dla każdego punktu pełnej geometrii BRouter (haversine między sąsiednimi). */
    private static double[] cumulativeKm(List<double[]> coords) {
        double[] cum = new double[coords.size()];
        for (int i = 1; i < coords.size(); i++) {
            cum[i] = cum[i - 1] + WaypointSelector.haversineKm(coords.get(i - 1), coords.get(i));
        }
        return cum;
    }

    /** Binary search najbliższego indeksu w {@code cumKm} dla docelowego kilometra. */
    private static int findClosestKmIdx(double[] cumKm, double targetKm) {
        if (cumKm.length == 0) return 0;
        if (targetKm <= 0) return 0;
        if (targetKm >= cumKm[cumKm.length - 1]) return cumKm.length - 1;
        int lo = 0, hi = cumKm.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumKm[mid] <= targetKm) lo = mid;
            else hi = mid;
        }
        // wybierz bliższy z dwóch
        return (Math.abs(cumKm[lo] - targetKm) <= Math.abs(cumKm[hi] - targetKm)) ? lo : hi;
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
