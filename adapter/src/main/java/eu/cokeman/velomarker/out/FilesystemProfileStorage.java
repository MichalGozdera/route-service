package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.port.out.ProfileStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class FilesystemProfileStorage implements ProfileStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemProfileStorage.class);
    private static final String EXT = ".brf";

    private final Path profilesDir;

    public FilesystemProfileStorage(@Value("${brouter.profiles-dir:./brouter-deploy/profiles2}") String profilesDir) throws IOException {
        this.profilesDir = resolveProfilesDir(profilesDir);
        Files.createDirectories(this.profilesDir);
        log.info("Profiles directory: {}", this.profilesDir);
    }

    /** Mirror of FilesystemSegmentStorage.resolveSegmentsDir — anchor at brouter-deploy/Dockerfile. */
    private static Path resolveProfilesDir(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.normalize();
        Path cursor = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path marker = cursor.resolve("brouter-deploy").resolve("Dockerfile");
            if (Files.isRegularFile(marker)) {
                return cursor.resolve("brouter-deploy").resolve("profiles2").normalize();
            }
            cursor = cursor.getParent();
        }
        return Paths.get("").toAbsolutePath().resolve(p).normalize();
    }

    @Override
    public List<Profile> list() {
        List<Profile> out = new ArrayList<>();
        try (var stream = Files.list(profilesDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXT)).forEach(p -> {
                try {
                    String name = stripExt(p.getFileName().toString());
                    long size = Files.size(p);
                    var mtime = Files.getLastModifiedTime(p).toInstant();
                    out.add(new Profile(name, size, mtime));
                } catch (IOException e) {
                    log.warn("Failed to stat {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list profiles dir {}: {}", profilesDir, e.getMessage());
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    @Override
    public Optional<ProfileContent> read(String name) throws IOException {
        Path p = profilesDir.resolve(name + EXT);
        if (!Files.exists(p)) return Optional.empty();
        String content = Files.readString(p, StandardCharsets.UTF_8);
        long size = Files.size(p);
        var mtime = Files.getLastModifiedTime(p).toInstant();
        return Optional.of(new ProfileContent(name, content, size, mtime));
    }

    @Override
    public Profile save(String name, String content) throws IOException {
        Path target = profilesDir.resolve(name + EXT);
        Path tmp = profilesDir.resolve(name + EXT + ".tmp");
        Files.deleteIfExists(tmp);
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(target);
        Instant mtime = Files.getLastModifiedTime(target).toInstant();
        log.info("Saved profile {} ({} bytes)", name, size);
        return new Profile(name, size, mtime);
    }

    @Override
    public boolean delete(String name) throws IOException {
        return Files.deleteIfExists(profilesDir.resolve(name + EXT));
    }

    private static String stripExt(String fname) {
        return fname.endsWith(EXT) ? fname.substring(0, fname.length() - EXT.length()) : fname;
    }
}
