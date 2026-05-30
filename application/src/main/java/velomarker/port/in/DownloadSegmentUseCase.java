package velomarker.port.in;

import velomarker.entity.SegmentName;

public interface DownloadSegmentUseCase {
    /**
     * Schedules an async download. Returns a task descriptor that can be polled (TODO).
     * For now, fire-and-forget; UI reloads the segment list to see the new file appear.
     */
    SegmentTask startDownload(SegmentName name);

    enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    record SegmentTask(String taskId, SegmentName segment, Status status) {}
}
