package velomarker.port.out;

import velomarker.entity.DemTileName;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Local filesystem operations on DEM tiles (Copernicus GLO-30 .tif). Writes are
 * atomic: implementations write to a temp file then ATOMIC_MOVE into place so
 * the elevation server never observes a partial file.
 */
public interface DemTileStorage {

    List<InstalledDemTile> listInstalled();

    Optional<InstalledDemTile> find(DemTileName name);

    OutputStream openTempForWrite(DemTileName name) throws IOException;

    void commit(DemTileName name) throws IOException;

    void abort(DemTileName name);

    boolean delete(DemTileName name) throws IOException;

    record InstalledDemTile(DemTileName name, long sizeBytes, Instant modifiedAt) {}
}
