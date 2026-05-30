package velomarker.port.out;

import velomarker.entity.ElevationProfile;

import java.util.List;

public interface ElevationDataSource {

    /**
     * Samples elevation along the given coordinates [[lng, lat], ...].
     * Implementations may downsample input to a configurable max-samples cap.
     */
    ElevationProfile sample(List<double[]> coordinates);

    /**
     * Per-call override of max-samples. Used by DaySplitter for higher-resolution
     * profile (e.g. 2000 samples) needed to avoid losing elevation gain between
     * sparse samples. Default impl ignores maxSamples and delegates to sample(coords).
     */
    default ElevationProfile sample(List<double[]> coordinates, int maxSamples) {
        return sample(coordinates);
    }
}
