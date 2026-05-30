package velomarker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.DemTileInfo;
import velomarker.entity.DemTileName;
import velomarker.port.in.DeleteDemTileUseCase;
import velomarker.port.in.DownloadDemTileUseCase;
import velomarker.port.in.ListDemTilesUseCase;
import velomarker.port.out.DemTileRemoteSource;
import velomarker.port.out.DemTileRemoteSource.RemoteDemTile;
import velomarker.port.out.DemTileStorage;
import velomarker.port.out.DemTileStorage.InstalledDemTile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Analogous to SegmentManagementService — orchestrates Copernicus DEM tile
 * listing, async downloads, and deletes. No worker restart needed: Open Topo
 * Data picks up new tiles on next request (filesystem scan).
 */
public class DemTileManagementService
        implements ListDemTilesUseCase, DownloadDemTileUseCase, DeleteDemTileUseCase {

    private static final Logger log = LoggerFactory.getLogger(DemTileManagementService.class);

    private final DemTileRemoteSource remoteSource;
    private final DemTileStorage storage;
    private final ExecutorService downloadExecutor;
    private final Map<String, DemTileTask> tasks = new HashMap<>();

    public DemTileManagementService(DemTileRemoteSource remoteSource, DemTileStorage storage) {
        this.remoteSource = remoteSource;
        this.storage = storage;
        this.downloadExecutor = Executors.newFixedThreadPool(2, namedThreadFactory());
    }

    @Override
    public List<DemTileInfo> listAll(boolean europeOnly) {
        List<RemoteDemTile> remote;
        try {
            remote = remoteSource.listAvailable(europeOnly);
        } catch (Exception e) {
            log.warn("Remote DEM catalog unavailable — falling back to installed-only view", e);
            remote = List.of();
        }
        List<InstalledDemTile> installed = storage.listInstalled();
        Map<String, InstalledDemTile> installedByName = new HashMap<>();
        for (InstalledDemTile s : installed) installedByName.put(s.name().name(), s);

        Map<String, DemTileInfo> merged = new HashMap<>();
        for (RemoteDemTile r : remote) {
            InstalledDemTile loc = installedByName.get(r.name().name());
            merged.put(r.name().name(), new DemTileInfo(
                    r.name(),
                    r.sizeBytes(),
                    loc != null,
                    loc != null ? loc.sizeBytes() : null,
                    loc != null ? loc.modifiedAt() : null));
        }
        for (InstalledDemTile loc : installed) {
            if (!merged.containsKey(loc.name().name())) {
                merged.put(loc.name().name(), new DemTileInfo(
                        loc.name(),
                        null,
                        true,
                        loc.sizeBytes(),
                        loc.modifiedAt()));
            }
        }

        List<DemTileInfo> out = new ArrayList<>(merged.values());
        if (europeOnly) out.removeIf(t -> !t.name().isInEurope());
        out.sort((a, b) -> {
            int cmp = Integer.compare(b.name().latStart(), a.name().latStart());
            if (cmp != 0) return cmp;
            return Integer.compare(a.name().lonStart(), b.name().lonStart());
        });
        return out;
    }

    @Override
    public DemTileTask startDownload(DemTileName name) {
        String taskId = UUID.randomUUID().toString();
        DemTileTask task = new DemTileTask(taskId, name, Status.QUEUED);
        synchronized (tasks) { tasks.put(taskId, task); }
        downloadExecutor.submit(() -> runDownload(taskId, name));
        return task;
    }

    private void runDownload(String taskId, DemTileName name) {
        updateTask(taskId, Status.RUNNING);
        OutputStream out = null;
        try {
            out = storage.openTempForWrite(name);
            final OutputStream sink = out;
            remoteSource.downloadTo(name, sink, (transferred, expected) -> {
                if (expected > 0 && transferred % (10L * 1024 * 1024) == 0) {
                    log.debug("Downloading DEM {}: {}/{} bytes", name.name(), transferred, expected);
                }
            });
            sink.close();
            out = null;
            storage.commit(name);
            updateTask(taskId, Status.COMPLETED);
            log.info("Downloaded DEM tile {}", name.name());
        } catch (Exception e) {
            log.error("DEM download failed for {}: {}", name.name(), e.getMessage(), e);
            storage.abort(name);
            updateTask(taskId, Status.FAILED);
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void updateTask(String taskId, Status status) {
        synchronized (tasks) {
            DemTileTask existing = tasks.get(taskId);
            if (existing != null) {
                tasks.put(taskId, new DemTileTask(existing.taskId(), existing.tile(), status));
            }
        }
    }

    @Override
    public void delete(DemTileName name) {
        try {
            boolean deleted = storage.delete(name);
            if (deleted) log.info("Deleted DEM tile {}", name.name());
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete DEM tile " + name.name(), e);
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "dem-download-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
