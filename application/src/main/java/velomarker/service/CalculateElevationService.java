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
        // Pełna granulacja — bez tego default cap 500 dla długich tras zaniżał gain o 20-30%
        // (sampling co kilka km gubi wzgórki). Spójne z per-day planera (sample(coords, size)).
        ElevationProfile p = source.sample(coordinates, coordinates.size());
        log.info("ROUTE-ELEVATION call: in={} coords, profile={} points, gain={}m, loss={}m",
                coordinates.size(),
                p.profile() != null ? p.profile().size() : 0,
                Math.round(p.gainM()), Math.round(p.lossM()));
        return p;
    }
}
