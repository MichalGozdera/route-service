package velomarker.port.out;

import velomarker.entity.DemTileName;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Copernicus DEM GLO-30 catalog + binary download (AWS Open Data S3, no auth).
 * Bucket: copernicus-dem-30m.s3.amazonaws.com
 */
public interface DemTileRemoteSource {

    /** Catalog of all available DEM tiles + remote sizes (HEAD requests). EU bbox only by default. */
    List<RemoteDemTile> listAvailable(boolean europeOnly);

    /** Streams a .tif binary into the given sink. Caller owns the stream lifecycle. */
    void downloadTo(DemTileName name, OutputStream sink, ProgressListener progress) throws IOException;

    record RemoteDemTile(DemTileName name, long sizeBytes) {}

    @FunctionalInterface
    interface ProgressListener {
        void onBytesTransferred(long total, long expected);
    }
}
