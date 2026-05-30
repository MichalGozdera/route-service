package velomarker.port.in;

import velomarker.entity.ElevationProfile;

import java.util.List;

public interface CalculateElevationUseCase {

    ElevationProfile calculate(List<double[]> coordinates);
}
