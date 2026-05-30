package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.DemTileName;
import velomarker.port.out.DemTileRemoteSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Copernicus DEM GLO-30 tiles from AWS Open Data (anonymous S3, no auth).
 *
 * Naming on S3: each tile is in its own prefix —
 *   s3://copernicus-dem-30m/Copernicus_DSM_COG_10_N50_00_E020_00_DEM/
 *     Copernicus_DSM_COG_10_N50_00_E020_00_DEM.tif
 *
 * We expose the catalog as a static 1°×1° grid (EU bbox by default); remote size
 * is fetched lazily via HEAD on first listAll call. Tiles outside Copernicus DEM
 * coverage (e.g. ocean-only) will 404 on HEAD/GET — we report size=0 and the
 * download will simply fail with a clear log line.
 */
@Component
public class HttpCopernicusDemRemoteSource implements DemTileRemoteSource {

    private static final Logger log = LoggerFactory.getLogger(HttpCopernicusDemRemoteSource.class);

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpCopernicusDemRemoteSource(
            @Value("${route.elevation.tile-source-url:https://copernicus-dem-30m.s3.amazonaws.com}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        log.info("Copernicus DEM remote source: {}", this.baseUrl);
    }

    @Override
    public List<RemoteDemTile> listAvailable(boolean europeOnly) {
        List<RemoteDemTile> out = new ArrayList<>();
        int latMin = europeOnly ? 30 : -90;
        int latMax = europeOnly ? 72 : 89;
        int lonMin = europeOnly ? -15 : -180;
        int lonMax = europeOnly ? 45 : 179;

        for (int lat = latMax; lat >= latMin; lat--) {
            for (int lon = lonMin; lon <= lonMax; lon++) {
                DemTileName name = DemTileName.of(lon, lat);
                // size=0 means "size unknown" — UI shows "—" and HEAD-on-demand is up to the user.
                // We don't HEAD-blast all ~3000 EU tiles here.
                out.add(new RemoteDemTile(name, 0L));
            }
        }
        return out;
    }

    @Override
    public void downloadTo(DemTileName name, OutputStream sink, ProgressListener progress) throws IOException {
        String tileUrl = urlFor(name);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tileUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        log.info("Downloading DEM tile from {}", tileUrl);
        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 404) {
                throw new IOException("DEM tile not available at source (404): " + name.name() +
                        " — Copernicus DEM has no coverage here (likely ocean)");
            }
            if (resp.statusCode() != 200) {
                throw new IOException("DEM source HTTP " + resp.statusCode() + " for " + name.name());
            }
            long expected = resp.headers().firstValueAsLong("content-length").orElse(-1);
            try (InputStream in = resp.body()) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    sink.write(buf, 0, n);
                    total += n;
                    if (progress != null) progress.onBytesTransferred(total, expected);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /**
     * Convert DemTileName like "N50E020" into the Copernicus S3 path:
     *   /Copernicus_DSM_COG_10_N50_00_E020_00_DEM/Copernicus_DSM_COG_10_N50_00_E020_00_DEM.tif
     */
    private String urlFor(DemTileName name) {
        int lat = name.latStart();
        int lon = name.lonStart();
        String latPart = (lat >= 0 ? "N" : "S") + String.format("%02d", Math.abs(lat));
        String lonPart = (lon >= 0 ? "E" : "W") + String.format("%03d", Math.abs(lon));
        String stem = "Copernicus_DSM_COG_10_" + latPart + "_00_" + lonPart + "_00_DEM";
        return baseUrl + "/" + stem + "/" + stem + ".tif";
    }
}
