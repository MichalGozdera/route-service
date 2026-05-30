package velomarker.entity;

import java.util.List;

public record ElevationProfile(
        List<double[]> profile,
        int gainM,
        int lossM,
        int minEleM,
        int maxEleM
) {
}
