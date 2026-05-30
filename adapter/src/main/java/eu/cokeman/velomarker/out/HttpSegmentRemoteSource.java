package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.SegmentName;
import velomarker.port.out.SegmentRemoteSource;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HttpSegmentRemoteSource implements SegmentRemoteSource {

    private static final Logger log = LoggerFactory.getLogger(HttpSegmentRemoteSource.class);

    /** Matches `<a href="E20_N50.rd5">E20_N50.rd5</a>   DATE TIME   12345` */
    private static final Pattern LISTING_LINE = Pattern.compile(
            "<a href=\"([EW]\\d+_[NS]\\d+)\\.rd5\">[^<]+</a>\\s+\\S+\\s+\\S+\\s+(\\d+)");

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpSegmentRemoteSource(@Value("${brouter.remote.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public List<RemoteSegment> listAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("brouter.de returned HTTP " + resp.statusCode());
            }
            List<RemoteSegment> out = new ArrayList<>();
            Matcher m = LISTING_LINE.matcher(resp.body());
            while (m.find()) {
                try {
                    SegmentName name = SegmentName.parse(m.group(1));
                    long size = Long.parseLong(m.group(2));
                    out.add(new RemoteSegment(name, size));
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping unparseable listing entry: {}", m.group(0));
                }
            }
            log.info("Fetched {} remote segments from {}", out.size(), baseUrl);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch BRouter segments listing: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadTo(SegmentName name, OutputStream sink, ProgressListener progress) throws IOException {
        URI uri = URI.create(baseUrl + name.fileName());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IOException("Download " + name.fileName() + " HTTP " + resp.statusCode());
            }
            long expected = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
            try (InputStream in = resp.body()) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    sink.write(buf, 0, n);
                    total += n;
                    if (progress != null) progress.onBytesTransferred(total, expected);
                }
                sink.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }
}
