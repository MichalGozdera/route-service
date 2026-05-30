package velomarker.entity;

import java.time.Instant;

/**
 * Merged view of a Copernicus DEM tile — remote catalog size + local installed state.
 */
public record DemTileInfo(
        DemTileName name,
        Long remoteSizeBytes,
        boolean installed,
        Long installedSizeBytes,
        Instant installedAt) {

    public boolean isOutdated() {
        if (!installed || remoteSizeBytes == null || installedSizeBytes == null) return false;
        return !remoteSizeBytes.equals(installedSizeBytes);
    }
}
