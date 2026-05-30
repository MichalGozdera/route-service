package velomarker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.ElevationProfile;
import velomarker.port.in.CalculateElevationUseCase;
import velomarker.port.out.ElevationDataSource;

import java.util.List;

public class CalculateElevationService implements CalculateElevationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CalculateElevationService.class);

    private final ElevationDataSource source;

    public CalculateElevationService(ElevationDataSource source) {
        this.source = source;
    }

    @Override
    public ElevationProfile calculate(List<double[]> coordinates) {
        if (coordinates == null || coordinates.size() < 2) {
            throw new IllegalArgumentException("At least 2 coordinates required");
        }
        log.debug("Sampling elevation for {} coordinates", coordinates.size());
        return source.sample(coordinates);
    }
}
