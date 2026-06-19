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

    /**
     * Pre-fault (mmap + dotknij stron) wszystkie kafle DEM pokrywające bbox planu, by pierwsze próbkowanie
     * wysokości w trakcie planowania nie płaciło rzadkich cold page-faultów (~100 ms w p99). Best-effort,
     * default no-op. bbox = [minLng, minLat, maxLng, maxLat].
     */
    default void preload(double[] bbox) {
        // no-op
    }
}
