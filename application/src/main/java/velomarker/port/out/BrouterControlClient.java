package velomarker.port.out;

import java.util.List;

/** Talks to velomarker-brouter control-api (Python, port 17779). */
public interface BrouterControlClient {
    /** Best-effort rolling restart; swallows failures (logged). */
    void rollingRestart();

    /** Restart a single worker by index. Throws on failure (UI surfaces error). */
    void restartWorker(int index);

    /** Snapshot of supervisorctl-reported worker states. Empty on failure. */
    List<WorkerStatus> status();

    /** Lists known log sources (worker-N, nginx-access, control-api, …). */
    List<LogSource> listLogs();

    /** Tails the last {@code lines} lines of a named log. Empty string if missing/error. */
    String tailLog(String name, int lines);

    record WorkerStatus(String name, String state, Integer pid, String uptime) {}
    record LogSource(String name, String path, long sizeBytes, Long modifiedAtEpochSec) {}
}
