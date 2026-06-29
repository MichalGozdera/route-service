package velomarker.service.planning;

import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Rejestr stanu liczenia tras per-task — pozwala anulować liczenie z innego żądania/wątku.
public final class ComputationRegistry {

    private final Set<UUID> computing = ConcurrentHashMap.newKeySet();
    private final Set<UUID> cancelRequested = ConcurrentHashMap.newKeySet();

    public void begin(UUID taskId) {
        if (taskId != null) {
            cancelRequested.remove(taskId);
            computing.add(taskId);
        }
    }

    public void end(UUID taskId) {
        if (taskId != null) {
            computing.remove(taskId);
            cancelRequested.remove(taskId);
        }
    }

    public void requestCancel(UUID taskId) {
        if (taskId != null) {
            cancelRequested.add(taskId);
        }
    }

    public boolean isCancelRequested(UUID taskId) {
        return taskId != null && cancelRequested.contains(taskId);
    }

    /** Czy w TYM JVM trwa żywe liczenie tego taska (false dla osieroconego RUNNING po restarcie). */
    public boolean isComputing(UUID taskId) {
        return taskId != null && computing.contains(taskId);
    }

}
