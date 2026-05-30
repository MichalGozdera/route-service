package velomarker.port.in;

import velomarker.entity.DemTileInfo;

import java.util.List;

public interface ListDemTilesUseCase {
    List<DemTileInfo> listAll(boolean europeOnly);
}
