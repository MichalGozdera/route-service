package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.DemTileName;
import velomarker.port.out.DemTileStorage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pobiera kafle Copernicus (.tif) do bufora i tyle — KONWERSJĄ na 3″ SRTM HGT (.hgt) zajmuje się osobny kontener
 * {@code dem-converter} (obraz gdal), który pilnuje katalogu, robi .tif→.hgt i usuwa .tif. route-service NIE używa gdala
 * (ani w dev, ani w prod). „installed" = obecny .hgt (pojawia się chwilę po pobraniu, gdy konwerter przemieli .tif).
 * route-service czyta .hgt przez {@link LocalHgtElevationClient}.
 */
@Component
public class FilesystemDemTileStorage implements DemTileStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemDemTileStorage.class);

    private final Path tifDir;   // bufor pobrania .tif (konwerter go obserwuje i konsumuje)
    private final Path hgtDir;   // docelowe .hgt (pisze konwerter, czyta route-service)

    public FilesystemDemTileStorage(@Value("${route.elevation.tiles-dir}") String tilesDir,
                                    @Value("${route.elevation.hgt-dir}") String hgtDir) throws IOException {
        this.tifDir = resolveAgainstRepoRoot(tilesDir);
        this.hgtDir = resolveAgainstRepoRoot(hgtDir);
        Files.createDirectories(this.tifDir);
        Files.createDirectories(this.hgtDir);
        log.info("DEM tiles: tif-buffer={} hgt-cache={} (konwersja: kontener dem-converter)", this.tifDir, this.hgtDir);
    }

    /**
     * Ścieżka względna liczona od korzenia repo (marker {@code infra/elevation/README.md}) — tak by działało niezależnie
     * od CWD (route-service uruchamiany z różnych katalogów). Ścieżka absolutna używana wprost (prod: /data/...).
     */
    private static Path resolveAgainstRepoRoot(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.normalize();
        Path cursor = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            if (Files.isRegularFile(cursor.resolve("infra").resolve("elevation").resolve("README.md"))) {
                return cursor.resolve(p).normalize();
            }
            cursor = cursor.getParent();
        }
        return Paths.get("").toAbsolutePath().resolve(p).normalize();
    }

    @Override
    public List<InstalledDemTile> listInstalled() {
        List<InstalledDemTile> out = new ArrayList<>();
        try (var stream = Files.list(hgtDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".hgt")).forEach(p -> {
                try {
                    DemTileName name = DemTileName.parse(p.getFileName().toString());
                    out.add(new InstalledDemTile(name, Files.size(p), Files.getLastModifiedTime(p).toInstant()));
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping non-tile file {}: {}", p.getFileName(), e.getMessage());
                } catch (IOException e) {
                    log.warn("Failed to stat {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list HGT tiles dir {}: {}", hgtDir, e.getMessage());
        }
        return out;
    }

    @Override
    public Optional<InstalledDemTile> find(DemTileName name) {
        Path p = hgtDir.resolve(name.hgtFileName());
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(new InstalledDemTile(name, Files.size(p), Files.getLastModifiedTime(p).toInstant()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public OutputStream openTempForWrite(DemTileName name) throws IOException {
        Path tmp = tempPath(name);
        Files.deleteIfExists(tmp);
        return Files.newOutputStream(tmp);
    }

    @Override
    public void commit(DemTileName name) throws IOException {
        Path tmp = tempPath(name);
        Path tif = tifDir.resolve(name.fileName());
        if (!Files.exists(tmp)) throw new IOException("Temp file missing: " + tmp);
        Files.move(tmp, tif, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // .tif w buforze — konwerter (kontener dem-converter) zrobi .hgt i usunie .tif. „installed" pojawi się wtedy.
        log.info("Downloaded DEM tile {} ({} bytes) → awaiting dem-converter (.tif→.hgt)", name.name(), Files.size(tif));
    }

    @Override
    public boolean delete(DemTileName name) throws IOException {
        boolean hgtDeleted = Files.deleteIfExists(hgtDir.resolve(name.hgtFileName()));
        Files.deleteIfExists(tifDir.resolve(name.fileName()));   // sprzątnij ewentualną pozostałość .tif w buforze
        return hgtDeleted;
    }

    @Override
    public void abort(DemTileName name) {
        try {
            Files.deleteIfExists(tempPath(name));
        } catch (IOException e) {
            log.warn("Failed to clean up temp file for {}: {}", name.name(), e.getMessage());
        }
    }

    private Path tempPath(DemTileName name) {
        return tifDir.resolve(name.fileName() + ".tmp");
    }
}
