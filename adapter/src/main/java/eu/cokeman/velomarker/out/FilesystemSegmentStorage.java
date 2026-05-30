package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.SegmentName;
import velomarker.port.out.SegmentStorage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class FilesystemSegmentStorage implements SegmentStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemSegmentStorage.class);

    private final Path segmentsDir;

    public FilesystemSegmentStorage(@Value("${brouter.segments-dir}") String segmentsDir) throws IOException {
        this.segmentsDir = resolveSegmentsDir(segmentsDir);
        Files.createDirectories(this.segmentsDir);
        log.info("Segments directory: {}", this.segmentsDir);
    }

    /**
     * If the configured path is absolute, use it verbatim. Otherwise walk up from the current
     * working directory looking for {@code brouter-deploy/Dockerfile} — that uniquely identifies
     * the velomarker project root (a self-created {@code route-service/brouter-deploy/} only ever
     * holds a {@code segments4/} subdir, no Dockerfile, so it won't false-positive).
     */
    private static Path resolveSegmentsDir(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.normalize();

        Path cursor = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path marker = cursor.resolve("brouter-deploy").resolve("Dockerfile");
            if (Files.isRegularFile(marker)) {
                return cursor.resolve("brouter-deploy").resolve("segments4").normalize();
            }
            cursor = cursor.getParent();
        }
        // No marker found anywhere — fall back to wd-relative. Auto-created on demand.
        return Paths.get("").toAbsolutePath().resolve(p).normalize();
    }

    @Override
    public List<InstalledSegment> listInstalled() {
        List<InstalledSegment> out = new ArrayList<>();
        try (var stream = Files.list(segmentsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".rd5")).forEach(p -> {
                try {
                    SegmentName name = SegmentName.parse(p.getFileName().toString());
                    long size = Files.size(p);
                    var mtime = Files.getLastModifiedTime(p).toInstant();
                    out.add(new InstalledSegment(name, size, mtime));
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping non-segment file {}: {}", p.getFileName(), e.getMessage());
                } catch (IOException e) {
                    log.warn("Failed to stat {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list segments dir {}: {}", segmentsDir, e.getMessage());
        }
        return out;
    }

    @Override
    public Optional<InstalledSegment> find(SegmentName name) {
        Path p = segmentsDir.resolve(name.fileName());
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(new InstalledSegment(name, Files.size(p), Files.getLastModifiedTime(p).toInstant()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public OutputStream openTempForWrite(SegmentName name) throws IOException {
        Path tmp = tempPath(name);
        Files.deleteIfExists(tmp);
        return Files.newOutputStream(tmp);
    }

    @Override
    public void commit(SegmentName name) throws IOException {
        Path tmp = tempPath(name);
        Path target = segmentsDir.resolve(name.fileName());
        if (!Files.exists(tmp)) throw new IOException("Temp file missing: " + tmp);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.info("Committed segment {} ({} bytes)", name.name(), Files.size(target));
    }

    @Override
    public void abort(SegmentName name) {
        try {
            Files.deleteIfExists(tempPath(name));
        } catch (IOException e) {
            log.warn("Failed to clean up temp file for {}: {}", name.name(), e.getMessage());
        }
    }

    @Override
    public boolean delete(SegmentName name) throws IOException {
        return Files.deleteIfExists(segmentsDir.resolve(name.fileName()));
    }

    private Path tempPath(SegmentName name) {
        return segmentsDir.resolve(name.fileName() + ".tmp");
    }
}
