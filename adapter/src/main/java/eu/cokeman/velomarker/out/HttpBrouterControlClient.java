package eu.cokeman.velomarker.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.port.out.BrouterControlClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class HttpBrouterControlClient implements BrouterControlClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBrouterControlClient.class);

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpBrouterControlClient(@Value("${brouter.control-api-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void rollingRestart() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/control/rolling-restart"))
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Rolling restart returned HTTP {}: {}", resp.statusCode(), resp.body());
            } else {
                log.info("BRouter rolling restart triggered");
            }
        } catch (Exception e) {
            log.warn("Failed to trigger BRouter rolling restart: {}", e.getMessage());
        }
    }

    @Override
    public void restartWorker(int index) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/control/restart/" + index))
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Restart worker-" + index + " HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restart worker-" + index + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<WorkerStatus> status() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/control/status"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Status call returned HTTP {}", resp.statusCode());
                return Collections.emptyList();
            }
            JsonNode arr = mapper.readTree(resp.body());
            List<WorkerStatus> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String name = n.path("name").asText();
                String state = n.path("state").asText();
                Integer pid = n.hasNonNull("pid") ? n.get("pid").asInt() : null;
                String uptime = n.hasNonNull("uptime") ? n.get("uptime").asText() : null;
                out.add(new WorkerStatus(name, state, pid, uptime));
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to fetch BRouter status: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<LogSource> listLogs() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/control/logs"))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Collections.emptyList();
            JsonNode arr = mapper.readTree(resp.body());
            List<LogSource> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String name = n.path("name").asText();
                String path = n.path("path").asText();
                long size = n.path("sizeBytes").asLong();
                Long mtime = n.hasNonNull("modifiedAt") ? n.get("modifiedAt").asLong() : null;
                out.add(new LogSource(name, path, size, mtime));
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to list logs: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String tailLog(String name, int lines) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/control/logs/" + name + "?lines=" + lines))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                throw new IllegalArgumentException("Unknown log source: " + name);
            }
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Tail log HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to tail log " + name + ": " + e.getMessage(), e);
        }
    }
}
