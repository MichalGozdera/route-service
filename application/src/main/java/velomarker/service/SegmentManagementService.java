package velomarker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.SegmentInfo;
import velomarker.entity.SegmentName;
import velomarker.port.in.DeleteSegmentUseCase;
import velomarker.port.in.DownloadSegmentUseCase;
import velomarker.port.in.ListSegmentsUseCase;
import velomarker.port.out.BrouterControlClient;
import velomarker.port.out.SegmentRemoteSource;
import velomarker.port.out.SegmentRemoteSource.RemoteSegment;
import velomarker.port.out.SegmentStorage;
import velomarker.port.out.SegmentStorage.InstalledSegment;

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

public class SegmentManagementService
        implements ListSegmentsUseCase, DownloadSegmentUseCase, DeleteSegmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(SegmentManagementService.class);

    private final SegmentRemoteSource remoteSource;
    private final SegmentStorage storage;
    private final BrouterControlClient controlClient;
    private final ExecutorService downloadExecutor;
    private final Map<String, SegmentTask> tasks = new HashMap<>();

    public SegmentManagementService(SegmentRemoteSource remoteSource,
                                     SegmentStorage storage,
                                     BrouterControlClient controlClient) {
        this.remoteSource = remoteSource;
        this.storage = storage;
        this.controlClient = controlClient;
        this.downloadExecutor = Executors.newFixedThreadPool(2, namedThreadFactory());
    }

    @Override
    public List<SegmentInfo> listAll(boolean europeOnly) {
        List<RemoteSegment> remote;
        try {
            remote = remoteSource.listAvailable();
        } catch (Exception e) {
            log.warn("Remote catalog unavailable — falling back to installed-only view", e);
            remote = List.of();
        }
        List<InstalledSegment> installed = storage.listInstalled();
        Map<String, InstalledSegment> installedByName = new HashMap<>();
        for (InstalledSegment s : installed) installedByName.put(s.name().name(), s);

        // Merge: union of remote ∪ installed.
        Map<String, SegmentInfo> merged = new HashMap<>();
        for (RemoteSegment r : remote) {
            InstalledSegment loc = installedByName.get(r.name().name());
            merged.put(r.name().name(), new SegmentInfo(
                    r.name(),
                    r.sizeBytes(),
                    loc != null,
                    loc != null ? loc.sizeBytes() : null,
                    loc != null ? loc.modifiedAt() : null));
        }
        for (InstalledSegment loc : installed) {
            if (!merged.containsKey(loc.name().name())) {
                merged.put(loc.name().name(), new SegmentInfo(
                        loc.name(),
                        null,
                        true,
                        loc.sizeBytes(),
                        loc.modifiedAt()));
            }
        }

        List<SegmentInfo> out = new ArrayList<>(merged.values());
        if (europeOnly) out.removeIf(s -> !s.name().isInEurope());
        out.sort((a, b) -> {
            int cmp = Integer.compare(b.name().latStart(), a.name().latStart()); // north → south
            if (cmp != 0) return cmp;
            return Integer.compare(a.name().lonStart(), b.name().lonStart()); // west → east
        });
        return out;
    }

    @Override
    public SegmentTask startDownload(SegmentName name) {
        String taskId = UUID.randomUUID().toString();
        SegmentTask task = new SegmentTask(taskId, name, Status.QUEUED);
        synchronized (tasks) { tasks.put(taskId, task); }

        downloadExecutor.submit(() -> runDownload(taskId, name));
        return task;
    }

    private void runDownload(String taskId, SegmentName name) {
        updateTask(taskId, Status.RUNNING);
        OutputStream out = null;
        try {
            out = storage.openTempForWrite(name);
            final OutputStream sink = out;
            remoteSource.downloadTo(name, sink, (transferred, expected) -> {
                if (expected > 0 && transferred % (5L * 1024 * 1024) == 0) {
                    log.debug("Downloading {}: {}/{} bytes", name.name(), transferred, expected);
                }
            });
            sink.close();
            out = null;
            storage.commit(name);
            controlClient.rollingRestart();
            updateTask(taskId, Status.COMPLETED);
            log.info("Downloaded segment {}", name.name());
        } catch (Exception e) {
            log.error("Download failed for {}: {}", name.name(), e.getMessage(), e);
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
            SegmentTask existing = tasks.get(taskId);
            if (existing != null) {
                tasks.put(taskId, new SegmentTask(existing.taskId(), existing.segment(), status));
            }
        }
    }

    @Override
    public void delete(SegmentName name) {
        try {
            boolean deleted = storage.delete(name);
            if (deleted) {
                log.info("Deleted segment {}", name.name());
                controlClient.rollingRestart();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete segment " + name.name(), e);
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "segment-download-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
