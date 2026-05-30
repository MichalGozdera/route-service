package velomarker.entity;

import java.time.Instant;

/**
 * Merged view of a BRouter segment — remote catalog entry from brouter.de plus
 * local filesystem state. Either side may be null:
 *   - remoteSizeBytes = null → remote listing unavailable
 *   - installed = false → file not present locally
 */
public record SegmentInfo(
        SegmentName name,
        Long remoteSizeBytes,
        boolean installed,
        Long installedSizeBytes,
        Instant installedAt) {

    public boolean isOutdated() {
        if (!installed || remoteSizeBytes == null || installedSizeBytes == null) return false;
        return !remoteSizeBytes.equals(installedSizeBytes);
    }
}
