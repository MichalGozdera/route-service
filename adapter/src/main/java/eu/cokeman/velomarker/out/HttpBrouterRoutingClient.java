package eu.cokeman.velomarker.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import velomarker.entity.RouteCalculation;
import velomarker.exception.BrouterMissingTileException;
import velomarker.exception.BrouterUnavailableException;
import velomarker.exception.BrouterUpstreamException;
import velomarker.port.out.BrouterRoutingClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "route.brouter.mode", havingValue = "http", matchIfMissing = true)
public class HttpBrouterRoutingClient implements BrouterRoutingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBrouterRoutingClient.class);

    private final String routingUrl;
    private final Semaphore semaphore;
    private final int waitSeconds;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpBrouterRoutingClient(
            @Value("${brouter.routing-api-url}") String routingUrl,
            @Value("${route.calculate.max-concurrent:8}") int maxConcurrent,
            @Value("${route.calculate.semaphore-wait-seconds:5}") int waitSeconds,
            @Value("${route.calculate.brouter-timeout-seconds:180}") int brouterTimeoutSeconds) {
        this.routingUrl = routingUrl.endsWith("/") ? routingUrl.substring(0, routingUrl.length() - 1) : routingUrl;
        this.semaphore = new Semaphore(maxConcurrent);
        this.waitSeconds = waitSeconds;
        this.requestTimeout = Duration.ofSeconds(brouterTimeoutSeconds);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("BRouter routing client: url={} maxConcurrent={} waitSeconds={}",
                this.routingUrl, maxConcurrent, waitSeconds);
    }

    @Override
    public RouteCalculation calculate(List<double[]> waypoints, String profile, boolean computeStats) {
        // HTTP wariant (legacy, fallback gdy ROUTE_BROUTER_MODE=http) zawsze zwraca to co BRouter HTTP
        // wypluwa w GeoJSON — stats per-segment nie były nigdy budowane, więc flag jest nieaktywny.
        // Embedded jest jedynym ścieżką która korzysta z computeStats.
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrouterUpstreamException("Interrupted waiting for semaphore", e);
        }
        if (!acquired) {
            log.warn("BRouter semaphore saturated, rejecting request (queued={})",
                    semaphore.getQueueLength());
            throw new BrouterUnavailableException(Math.max(waitSeconds, 2));
        }
        try {
            return doCalculate(waypoints, profile);
        } finally {
            semaphore.release();
        }
    }

    private RouteCalculation doCalculate(List<double[]> waypoints, String profile) {
        String lonlats = buildLonlats(waypoints);
        String url = routingUrl + "/brouter?lonlats=" + URLEncoder.encode(lonlats, StandardCharsets.UTF_8)
                + "&profile=" + URLEncoder.encode(profile, StandardCharsets.UTF_8)
                + "&format=geojson";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BrouterUpstreamException("BRouter request timed out", e);
        } catch (Exception e) {
            throw new BrouterUpstreamException("BRouter request failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() == 503 || resp.statusCode() == 429) {
            throw new BrouterUnavailableException(Math.max(waitSeconds, 2));
        }
        if (resp.statusCode() != 200) {
            String body = resp.body();
            // Brakujący tile DEM (.rd5) jest akcjonalny dla usera — wyłuskujemy nazwę by raportować.
            // Np.: "datafile W10_N45.rd5 not found" → tileName="W10_N45".
            Matcher m = RD5_NOT_FOUND.matcher(body == null ? "" : body);
            if (m.find()) {
                throw new BrouterMissingTileException(m.group(1),
                        "BRouter brakuje tile DEM: " + m.group(1) + ".rd5");
            }
            throw new BrouterUpstreamException("BRouter returned HTTP " + resp.statusCode() + ": " + truncate(body));
        }

        return parseResponse(resp.body());
    }

    private static final Pattern RD5_NOT_FOUND = Pattern.compile("datafile\\s+([A-Z]\\d+_[A-Z]\\d+)\\.rd5\\s+not\\s+found");

    private RouteCalculation parseResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode features = root.path("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new BrouterUpstreamException("BRouter response missing features");
            }
            JsonNode feature = features.get(0);
            JsonNode coords = feature.path("geometry").path("coordinates");
            if (!coords.isArray()) {
                throw new BrouterUpstreamException("BRouter response missing geometry coordinates");
            }
            List<double[]> coordList = new ArrayList<>(coords.size());
            for (JsonNode c : coords) {
                if (c.isArray() && c.size() >= 2) {
                    // Drop elevation here — we don't expose CGIAR-CSI elevation embedded in .rd5.
                    // Elevation is fetched separately via POST /route/elevation (Copernicus DEM).
                    coordList.add(new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
                }
            }
            JsonNode props = feature.path("properties");
            double distMeters = parseDoubleProp(props, "track-length");
            List<int[]> flatSpans = parseFlatSpans(props.path("messages"), coordList);
            return new RouteCalculation(coordList, distMeters / 1000.0, flatSpans);
        } catch (BrouterUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new BrouterUpstreamException("Failed to parse BRouter response: " + e.getMessage(), e);
        }
    }

    /**
     * Z tabeli brouter {@code messages} (kolumny Longitude/Latitude w mikrostopniach = endpoint odcinka, WayTags = tagi OSM)
     * wyłuskuje zakresy indeksów wierzchołków leżących w tunelu/wiadukcie. Endpointy to dokładne wierzchołki geometrii
     * (mikrostopnie/1e6 == coord), więc mapujemy je forward-pointerem bez dryfu. Best-effort: gdy messages brak albo
     * endpoint nie matchuje wierzchołka, zwraca pustą listę (brak korekcji tuneli — routing nigdy nie pada).
     */
    private static List<int[]> parseFlatSpans(JsonNode messages, List<double[]> coords) {
        if (!messages.isArray() || messages.size() < 2 || coords.size() < 3) {
            return List.of();
        }
        JsonNode header = messages.get(0);
        int lonCol = columnIndex(header, "Longitude");
        int latCol = columnIndex(header, "Latitude");
        int tagsCol = columnIndex(header, "WayTags");
        if (lonCol < 0 || latCol < 0 || tagsCol < 0) {
            return List.of();
        }
        // Indeks wierzchołka po zaokrąglonych mikrostopniach (dokładne dopasowanie endpointów do geometrii).
        long[] keyLon = new long[coords.size()];
        long[] keyLat = new long[coords.size()];
        for (int k = 0; k < coords.size(); k++) {
            keyLon[k] = Math.round(coords.get(k)[0] * 1_000_000.0);
            keyLat[k] = Math.round(coords.get(k)[1] * 1_000_000.0);
        }

        List<int[]> spans = new ArrayList<>();
        int ptr = 0;          // forward-pointer po wierzchołkach
        int prevEndIdx = 0;   // endpoint poprzedniego odcinka (start trasy dla pierwszego)
        for (int r = 1; r < messages.size(); r++) {
            JsonNode row = messages.get(r);
            long mLon = parseLongCell(row, lonCol);
            long mLat = parseLongCell(row, latCol);
            int endIdx = -1;
            for (int k = ptr; k < coords.size(); k++) {
                if (keyLon[k] == mLon && keyLat[k] == mLat) {
                    endIdx = k;
                    break;
                }
            }
            if (endIdx < 0) {
                // Endpoint nie odnaleziony — geometria i messages się rozjechały; rezygnujemy z korekcji tuneli.
                return List.of();
            }
            if (isTunnelOrBridge(textCell(row, tagsCol)) && endIdx > prevEndIdx) {
                addOrMergeSpan(spans, prevEndIdx, endIdx);
            }
            ptr = endIdx;
            prevEndIdx = endIdx;
        }
        return spans;
    }

    /** Dokleja span [a,b], scalając z poprzednim jeśli się stykają (sąsiednie tunelowe odcinki = jeden ciągły tunel). */
    private static void addOrMergeSpan(List<int[]> spans, int a, int b) {
        if (!spans.isEmpty()) {
            int[] last = spans.get(spans.size() - 1);
            if (a <= last[1]) {
                last[1] = Math.max(last[1], b);
                return;
            }
        }
        spans.add(new int[]{a, b});
    }

    /** true gdy WayTags zawiera tunnel=… lub bridge=… z wartością inną niż „no". */
    private static boolean isTunnelOrBridge(String wayTags) {
        if (wayTags == null || wayTags.isEmpty()) {
            return false;
        }
        for (String token : wayTags.split(" ")) {
            if ((token.startsWith("tunnel=") || token.startsWith("bridge=")) && !token.endsWith("=no")) {
                return true;
            }
        }
        return false;
    }

    private static int columnIndex(JsonNode header, String name) {
        if (!header.isArray()) {
            return -1;
        }
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(header.get(i).asText())) {
                return i;
            }
        }
        return -1;
    }

    private static long parseLongCell(JsonNode row, int col) {
        try {
            return Long.parseLong(textCell(row, col).trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String textCell(JsonNode row, int col) {
        if (!row.isArray() || col < 0 || col >= row.size()) {
            return "";
        }
        return row.get(col).asText("");
    }

    private static double parseDoubleProp(JsonNode props, String key) {
        JsonNode n = props.path(key);
        if (n.isMissingNode()) return 0.0;
        if (n.isTextual()) {
            try { return Double.parseDouble(n.asText()); } catch (NumberFormatException e) { return 0.0; }
        }
        return n.asDouble(0.0);
    }

    private static String buildLonlats(List<double[]> waypoints) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            double[] wp = waypoints.get(i);
            if (i > 0) sb.append('|');
            sb.append(wp[0]).append(',').append(wp[1]);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
