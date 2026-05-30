package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.openapi.api.ElevationApi;
import eu.cokeman.velomarker.openapi.model.ElevationRequestDto;
import eu.cokeman.velomarker.openapi.model.ElevationResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.ElevationProfile;
import velomarker.port.in.CalculateElevationUseCase;

import java.util.ArrayList;
import java.util.List;

@RestController
public class ElevationController implements ElevationApi {

    private final CalculateElevationUseCase useCase;

    public ElevationController(CalculateElevationUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public ResponseEntity<ElevationResponseDto> calculateElevation(ElevationRequestDto req) {
        List<double[]> coords = new ArrayList<>(req.getCoordinates().size());
        for (List<Double> c : req.getCoordinates()) {
            double[] arr = new double[c.size()];
            for (int i = 0; i < c.size(); i++) arr[i] = c.get(i);
            coords.add(arr);
        }
        ElevationProfile profile = useCase.calculate(coords);

        ElevationResponseDto dto = new ElevationResponseDto();
        List<List<Double>> profileOut = new ArrayList<>(profile.profile().size());
        for (double[] p : profile.profile()) {
            profileOut.add(List.of(p[0], p[1]));
        }
        dto.setProfile(profileOut);
        dto.setGainM(profile.gainM());
        dto.setLossM(profile.lossM());
        dto.setMinEleM(profile.minEleM());
        dto.setMaxEleM(profile.maxEleM());
        return ResponseEntity.ok(dto);
    }
}
