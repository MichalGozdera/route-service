package velomarker.port.in;

import velomarker.entity.DemTileName;

public interface DownloadDemTileUseCase {
    DemTileTask startDownload(DemTileName name);

    enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    record DemTileTask(String taskId, DemTileName tile, Status status) {}
}
