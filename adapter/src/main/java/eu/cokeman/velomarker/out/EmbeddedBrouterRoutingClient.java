package eu.cokeman.velomarker.out;

import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.RouteCalculation;
import velomarker.entity.RouteStats;
import velomarker.exception.BrouterMissingTileException;
import velomarker.exception.BrouterUnavailableException;
import velomarker.exception.BrouterUpstreamException;
import velomarker.port.out.BrouterRoutingClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded implementacja {@link BrouterRoutingClient} — bez HTTP roundtripa, BRouter jako biblioteka
 * Java w tej samej JVM. {@link RoutingEngine} dziedziczy po {@link Thread} i jest stateful per-request
 * (waypoints + {@link RoutingContext} przyjmuje wyłącznie w konstruktorze), więc reużywanie instancji
 * nie jest możliwe — każdy call tworzy świeży engine. Limit współbieżności pilnuje
 * {@link Semaphore} (N = {@code route.brouter.max-concurrent}, domyślnie 16).
 * <p>
 * Pamięć: segments {@code .rd5} są mmap'owane przez OS page cache i dzielone między równoczesne
 * engine'y — utworzenie kolejnej instancji nie multiplikuje footprintu segmentów (alokuje tylko
 * Java-side data structures + per-request buffory). Heap kosztu rośnie liniowo z liczbą równoczesnych
 * calls, ale jeden plan 300d odpala ~37k calls SEKWENCYJNIE z różnych krawędzi — nie wszystkie naraz.
 * <p>
 * Jedyny klient routingu BRoutera — JAR załadowany w proces route-service (zero HTTP, kontener
 * velomarker-brouter już nie istnieje). Błędy mapowane na domain exceptions, by warstwa wyżej
 * (CalculateRouteService, ControllerAdvice) nie znała szczegółów transportu.
 */
@Component
public class EmbeddedBrouterRoutingClient implements BrouterRoutingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBrouterRoutingClient.class);

    private static final Pattern RD5_NOT_FOUND = Pattern.compile("datafile\\s+([A-Z]\\d+_[A-Z]\\d+)\\.rd5\\s+not\\s+found");

    /**
     * Format wiersza zwracanego przez {@link OsmTrack#aggregateMessages()} w BRouter 1.7.x:
     * 13 kolumn oddzielonych TAB-em, w tej kolejności. Wartości:
     * <ul>
     *   <li>Longitude / Latitude — mikrostopnie int ((lon+180)*1e6)</li>
     *   <li>Elevation — metry</li>
     *   <li>Distance — metry odcinka (od poprzedniego wierzchołka)</li>
     *   <li>CostPerKm — wewn. metryka BRoutera</li>
     *   <li>ElevCost / TurnCost / NodeCost / InitialCost — komponenty kary</li>
     *   <li>WayTags — tagi OSM ścieżki ({@code highway=primary surface=asphalt ...})</li>
     *   <li>NodeTags — tagi OSM wierzchołka ({@code traffic_signals=yes ...})</li>
     *   <li>Time — sekundy</li>
     *   <li>Energy — joule</li>
     * </ul>
     * HTTP RouteServer dorabiał ten nagłówek przy konwersji do GeoJSON; embedded musi sam.
     */
    static final String BROUTER_MESSAGE_HEADER =
            "Longitude\tLatitude\tElevation\tDistance\tCostPerKm\tElevCost\tTurnCost\tNodeCost\t"
                    + "InitialCost\tWayTags\tNodeTags\tTime\tEnergy";

    /**
     * Seam testowy — produkcja tworzy real {@link RoutingEngine}, test wstrzykuje mock by uniknąć
     * inicjalizacji wątku BRoutera bez plików segments.
     */
    @FunctionalInterface
    interface RoutingEngineFactory {
        RoutingEngine create(List<OsmNodeNamed> waypoints, RoutingContext rc);
    }

    private final String profilesDir;
    private final Semaphore semaphore;
    private final int waitSeconds;
    private final int memoryClassMb;
    // Twardy limit czasu (ms) na pojedynczy call PRÓBNY (computeStats=false, planowanie). Patologiczne trasy
    // (target-island, las/woda) potrafią liczyć się 10-22 s i przez batch-barrier (f.get() na całym batchu)
    // dławią równoległość — rdzenie czekają na jeden zawieszony call. Po timeoucie → haversine fallback.
    // 0 = bez limitu (finalny routing computeStats=true ZAWSZE bez limitu).
    private final long probeTimeoutMs;
    private final RoutingEngineFactory engineFactory;

    // ─── Profilowanie BRouter calls — rolling histogram (last 200 sample), log co 200 calls ───
    // OSOBNO calc (doRun + parsing) i wait (czekanie na slot semafora) — wait > 0 = semafor pełny.
    private static final int TIMING_LOG_EVERY = 200;
    private static final int TIMING_SAMPLE_SIZE = 200;
    private final AtomicLong totalCalls = new AtomicLong();
    // Skumulowana suma CZASU CPU wszystkich calli (ms). Porównanie do wall-time planu daje realną
    // równoległość: parallelism ≈ Σcalc / wall. Σ≈wall → sekwencyjnie; Σ≈16×wall → wysycone 16 rdzeni.
    private final AtomicLong totalCalcMs = new AtomicLong();
    private final long[] calcMsSample = new long[TIMING_SAMPLE_SIZE]; // pure BRouter doRun time
    private final long[] waitMsSample = new long[TIMING_SAMPLE_SIZE]; // semaphore wait time
    private long lastLoggedAt = 0;

    @Autowired
    public EmbeddedBrouterRoutingClient(
            @Value("${brouter.segments-dir}") String segmentsDir,
            @Value("${brouter.profiles-dir}") String profilesDir,
            @Value("${route.brouter.max-concurrent:48}") int maxConcurrent,
            @Value("${route.calculate.semaphore-wait-seconds:5}") int waitSeconds,
            @Value("${route.brouter.memory-class-mb:256}") int memoryClassMb,
            @Value("${route.brouter.probe-timeout-ms:0}") long probeTimeoutMs) {
        this(profilesDir, maxConcurrent, waitSeconds, memoryClassMb, probeTimeoutMs, defaultFactory(new File(segmentsDir)));
        File segmentsFile = new File(segmentsDir);
        if (!segmentsFile.isDirectory()) {
            log.warn("BRouter segments dir does not exist (yet): {} — pierwszy routing call rzuci błąd. " +
                    "Stwórz katalog lub uruchom tile downloader.", segmentsFile.getAbsolutePath());
        }
        int cpuCores = Runtime.getRuntime().availableProcessors();
        log.info("Embedded BRouter routing client: segments={} profiles={} maxConcurrent={} waitSeconds={} (system CPU cores={})",
                segmentsFile.getAbsolutePath(), profilesDir, maxConcurrent, waitSeconds, cpuCores);
        if (maxConcurrent > cpuCores * 2) {
            log.warn("ROUTE_BROUTER_MAX_CONCURRENT={} > 2× CPU cores ({}). BRouter per call jest single-threaded — " +
                    "zbyt wiele wątków NA CPU = thrashing + GC pressure + mmap re-fault → p50 calc time rośnie 3-5× " +
                    "vs optymalna konfiguracja. Sugerowana wartość: {} (= cores) lub {} (= 1.5×cores).",
                    maxConcurrent, cpuCores, cpuCores, cpuCores * 3 / 2);
        }
    }

    /** Konstruktor dla testów (wstrzykiwana fabryka silnika). */
    EmbeddedBrouterRoutingClient(String profilesDir, int maxConcurrent, int waitSeconds,
                                 int memoryClassMb, long probeTimeoutMs, RoutingEngineFactory engineFactory) {
        this.profilesDir = trimTrailingSeparator(profilesDir);
        this.semaphore = new Semaphore(maxConcurrent);
        this.waitSeconds = waitSeconds;
        this.memoryClassMb = memoryClassMb > 0 ? memoryClassMb : 64;
        this.probeTimeoutMs = Math.max(0, probeTimeoutMs);
        this.engineFactory = engineFactory;
    }

    private static RoutingEngineFactory defaultFactory(File segmentsDir) {
        return (waypoints, rc) -> {
            RoutingEngine engine = new RoutingEngine(null, null, segmentsDir, waypoints, rc);
            // BRouter loguje cały found track jako GPX do System.out po doRun() — przydatne w CLI,
            // hałaśliwe w serwisie. quite=true wycisza zarówno GPX dump jak i outputMessage.
            engine.quite = true;
            return engine;
        };
    }

    @Override
    public RouteCalculation calculate(List<double[]> waypoints, String profile, boolean computeStats) {
        if (waypoints == null || waypoints.size() < 2) {
            throw new BrouterUpstreamException("Embedded BRouter expects at least 2 waypoints");
        }
        log.debug("BRouter calculate: {} waypoints, profile={}, computeStats={}", waypoints.size(), profile, computeStats);
        long tWaitStart = System.currentTimeMillis();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrouterUpstreamException("Interrupted waiting for semaphore", e);
        }
        if (!acquired) {
            log.warn("Embedded BRouter semaphore saturated (queued={})", semaphore.getQueueLength());
            throw new BrouterUnavailableException(Math.max(waitSeconds, 2));
        }
        long waitMs = System.currentTimeMillis() - tWaitStart;
        long tCalcStart = System.currentTimeMillis();
        try {
            RouteCalculation result = doCalculate(waypoints, profile, computeStats);
            long calcMs = System.currentTimeMillis() - tCalcStart;
            log.debug("BRouter calculate done: {} nodes, {} km, calc={}ms wait={}ms",
                    result.coordinates().size(), String.format("%.3f", result.distanceKm()), calcMs, waitMs);
            recordTiming(calcMs, waitMs);
            return result;
        } finally {
            semaphore.release();
        }
    }

    /** v3.16: reset liczników per plan coverage — „BRouter cumulative: calls=" pokazuje liczbę
     *  z TEGO planu, nie od startu serwisu (uwaga usera). Wołany na PLAN START. */
    @Override
    public synchronized void resetPlanCounters() {
        totalCalls.set(0);
        totalCalcMs.set(0);
        lastLoggedAt = 0;
    }

    /** Rolling sample: zapisz czas calc + wait + log percentyle co {@link #TIMING_LOG_EVERY} calls. */
    private void recordTiming(long calcMs, long waitMs) {
        long n = totalCalls.incrementAndGet();
        long cumCalcMs = totalCalcMs.addAndGet(calcMs);
        int slot = (int) ((n - 1) % TIMING_SAMPLE_SIZE);
        calcMsSample[slot] = calcMs;
        waitMsSample[slot] = waitMs;
        if (n - lastLoggedAt >= TIMING_LOG_EVERY) {
            synchronized (this) {
                if (n - lastLoggedAt < TIMING_LOG_EVERY) return;
                lastLoggedAt = n;
                int sampleSize = (int) Math.min(n, TIMING_SAMPLE_SIZE);
                Percentiles calc = percentiles(calcMsSample, sampleSize);
                Percentiles wait = percentiles(waitMsSample, sampleSize);
                log.info("BRouter calc time   (last {} of {}): min={}ms p50={}ms avg={}ms p95={}ms p99={}ms max={}ms",
                        new Object[]{sampleSize, n, calc.min, calc.p50, String.format("%.1f", calc.avg), calc.p95, calc.p99, calc.max});
                // CPU-time skumulowany: porównaj do wall-time fazy (z logów plannera) → realna równoległość.
                log.info("BRouter cumulative   : calls={} ΣcalcCpu={}s (Σ/wall = realna równoległość; cel ~16 na 16 rdzeni)",
                        new Object[]{n, cumCalcMs / 1000});
                log.info("BRouter wait time   (last {} of {}): min={}ms p50={}ms avg={}ms p95={}ms p99={}ms max={}ms",
                        new Object[]{sampleSize, n, wait.min, wait.p50, String.format("%.1f", wait.avg), wait.p95, wait.p99, wait.max});
                // Diagnostyka: który ze składników dominuje + sugestia akcji.
                if (wait.p50 > 50) {
                    log.warn("→ wait p50={}ms wysokie — semafor wysycony. Podnieś ROUTE_BROUTER_MAX_CONCURRENT.", wait.p50);
                }
                int cpuCores = Runtime.getRuntime().availableProcessors();
                if (calc.p50 > 150 && semaphore.availablePermits() < 2 && cpuCores > 0
                        && (semaphore.getQueueLength() + (cpuCores)) < cpuCores * 2) {
                    log.warn("→ calc p50={}ms wysokie ({}× CPU cores={}). Typowy embedded BRouter call = 30-80ms. " +
                            "Możliwa przyczyna: za dużo równoczesnych engine'ów. Zredukuj ROUTE_BROUTER_MAX_CONCURRENT do {}.",
                            new Object[]{calc.p50, calc.p50 / 50, cpuCores, cpuCores});
                }
            }
        }
    }

    private record Percentiles(long min, long p50, double avg, long p95, long p99, long max) {}

    private static Percentiles percentiles(long[] sample, int sampleSize) {
        long[] copy = Arrays.copyOf(sample, sampleSize);
        Arrays.sort(copy);
        long min = copy[0];
        long max = copy[sampleSize - 1];
        long p50 = copy[sampleSize * 50 / 100];
        long p95 = copy[Math.min(sampleSize - 1, sampleSize * 95 / 100)];
        long p99 = copy[Math.min(sampleSize - 1, sampleSize * 99 / 100)];
        double avg = Arrays.stream(copy).sum() / (double) sampleSize;
        return new Percentiles(min, p50, avg, p95, p99, max);
    }

    private RouteCalculation doCalculate(List<double[]> waypoints, String profile, boolean computeStats) {
        RoutingContext rc = new RoutingContext();
        rc.localFunction = profilesDir + "/" + profile + ".brf";
        // Limit pamięci microcache per-trasa. Domyślny BRouter 64 MB → przy szerokim szukaniu A*
        // (ultra-gminy) panic-mode eviction + re-dekodowanie w trakcie callu. Patrz route.brouter.memory-class-mb.
        rc.memoryclass = memoryClassMb;
        if (computeStats) {
            // Wymuś by BRouter populował per-segment messages (WayTags/NodeTags). Bez tego
            // OsmPath.computeOutput nie zapisuje wayKeyValues do MessageData → aggregateMessages()
            // zwraca pustą listę → RouteStatsLogger nie ma czego logować, FlatSpanParser nie wykrywa
            // tuneli. RouteServer kontenera BRouter robił to per query param "messages=1".
            // Wszystkie te flagi DROGIE per-segment (BRouter buduje message strings + turn instructions
            // + dodatkowe tagi z OSM); włączamy je TYLKO gdy faktycznie zużyjemy wynik. Dla intermediate
            // Coverage calls (~10k+ per coverage plan) to oszczędność ~50-100ms per call.
            rc.showspeed = true;
            rc.showSpeedProfile = true;
            rc.showTime = true;
            rc.turnInstructionMode = 9; // 9 = generate inline turn instructions w track.messageList
            // Dorzucaj WSZYSTKIE OSM tagi do wayKeyValues (nie tylko te z profilu .brf). Bez tego brak
            // `ref=*` w WayTags → HumanRouteStatsLogger nie pokaże "[DK7]"/"[D38]". Drogie!
            rc.processUnusedTags = true;
        }

        List<OsmNodeNamed> wpts = new ArrayList<>(waypoints.size());
        for (int i = 0; i < waypoints.size(); i++) {
            double[] wp = waypoints.get(i);
            OsmNodeNamed n = new OsmNodeNamed();
            n.ilon = lonToMicrodegrees(wp[0]);
            n.ilat = latToMicrodegrees(wp[1]);
            n.name = (i == 0) ? "from" : (i == waypoints.size() - 1 ? "to" : "via" + i);
            wpts.add(n);
        }

        RoutingEngine engine = engineFactory.create(wpts, rc);
        try {
            // Call próbny (planowanie) dostaje twardy limit czasu → patologiczne trasy padają szybko
            // (haversine fallback) zamiast dławić batch-barrier. Finalny routing (computeStats) bez limitu.
            engine.doRun(computeStats ? 0L : probeTimeoutMs);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throw mapBrouterError(msg, e);
        }

        String err = engine.getErrorMessage();
        if (err != null) {
            throw mapBrouterError(err, null);
        }

        OsmTrack track = engine.getFoundTrack();
        if (track == null || track.nodes == null || track.nodes.isEmpty()) {
            throw new BrouterUpstreamException("BRouter returned empty track");
        }

        // BRouter doRun() ZAWSZE ustawia track.messageList = [track.message] (1-elementowa summary).
        // Per-segment dane (z WayTags) są w OsmPathElement.message.wayKeyValues — pobieramy je przez
        // aggregateMessages() TYLKO gdy computeStats=true (gdy false: skip parsing dla planning probing
        // calls — 11k+ per coverage plan, każdy parsing kosztuje CPU, a stats i tak by były ignorowane).
        List<double[]> coordList = new ArrayList<>(track.nodes.size());
        for (OsmPathElement n : track.nodes) {
            coordList.add(new double[]{microdegreesToLon(n.getILon()), microdegreesToLat(n.getILat())});
        }
        double distMeters = track.distance;

        if (!computeStats) {
            // Skip cały stats + flatSpans build. FlatSpans potrzebne tylko do korekcji wysokości tuneli,
            // a planning Coverage probing calls geometrii nie używają do elevation — używają jej do oceny
            // cost'u w cache. Zostawiamy empty by oszczędzić ~kilka ms × 10k calls = sekundy CPU.
            return new RouteCalculation(coordList, distMeters / 1000.0, List.of(), RouteStats.empty());
        }

        List<String> aggregated = track.aggregateMessages();
        int aggregatedSize = aggregated != null ? aggregated.size() : 0;
        if (aggregatedSize >= 1) {
            List<String> withHeader = new ArrayList<>(aggregatedSize + 1);
            withHeader.add(BROUTER_MESSAGE_HEADER);
            withHeader.addAll(aggregated);
            track.messageList = withHeader;
        }
        log.debug("BRouter track: nodes={}, distance={} m, aggregated-rows={}",
                track.nodes.size(), track.distance, aggregatedSize);

        List<int[]> flatSpans = FlatSpanParser.parse(track.messageList, coordList);

        // RouteStatsBuilder potrzebuje coords by przemapować endpointy z messageList (mikrostopnie)
        // na indeksy w geometrii — robi to dla spans per kategoria (surface/road/smoothness).
        RouteStats stats = RouteStatsBuilder.build(track, coordList);
        HumanRouteStatsLogger.log(log, stats, profile);
        if (log.isDebugEnabled()) {
            RouteStatsLogger.log(log, track, profile);
        }

        return new RouteCalculation(coordList, distMeters / 1000.0, flatSpans, stats);
    }

    private RuntimeException mapBrouterError(String msg, Throwable cause) {
        Matcher m = RD5_NOT_FOUND.matcher(msg);
        if (m.find()) {
            return new BrouterMissingTileException(m.group(1), "BRouter brakuje tile DEM: " + m.group(1) + ".rd5");
        }
        return cause == null
                ? new BrouterUpstreamException("BRouter error: " + truncate(msg))
                : new BrouterUpstreamException("BRouter error: " + truncate(msg), cause);
    }

    /** Konwersja {@code lng} (W/E ze znakiem) na BRouter microdegrees: {@code (lon+180)*1e6}. */
    static int lonToMicrodegrees(double lon) {
        return (int) Math.round((lon + 180.0) * 1_000_000.0);
    }

    /** Konwersja {@code lat} (N/S ze znakiem) na BRouter microdegrees: {@code (lat+90)*1e6}. */
    static int latToMicrodegrees(double lat) {
        return (int) Math.round((lat + 90.0) * 1_000_000.0);
    }

    static double microdegreesToLon(int ilon) {
        return ilon / 1_000_000.0 - 180.0;
    }

    static double microdegreesToLat(int ilat) {
        return ilat / 1_000_000.0 - 90.0;
    }

    private static String trimTrailingSeparator(String s) {
        if (s == null || s.isEmpty()) return s;
        char last = s.charAt(s.length() - 1);
        return (last == '/' || last == '\\') ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
