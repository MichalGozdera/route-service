package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.mapper.RouteDraftExternalMapper;
import eu.cokeman.velomarker.openapi.api.RoutingApi;
import eu.cokeman.velomarker.openapi.model.CalculateRouteRequestDto;
import eu.cokeman.velomarker.openapi.model.CalculateRouteResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.RouteCalculation;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.in.CalculateRouteUseCase.CalculateRouteCommand;

import java.util.ArrayList;
import java.util.List;

@RestController
public class RoutingController implements RoutingApi {

    private final CalculateRouteUseCase useCase;
    private final RouteDraftExternalMapper mapper;

    public RoutingController(CalculateRouteUseCase useCase, RouteDraftExternalMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<CalculateRouteResponseDto> calculateRoute(CalculateRouteRequestDto req) {
        List<double[]> waypoints = new ArrayList<>(req.getWaypoints().size());
        for (List<Double> wp : req.getWaypoints()) {
            double[] arr = new double[wp.size()];
            for (int i = 0; i < wp.size(); i++) arr[i] = wp.get(i);
            waypoints.add(arr);
        }
        RouteCalculation result = useCase.calculate(new CalculateRouteCommand(waypoints, req.getProfile()));

        CalculateRouteResponseDto dto = new CalculateRouteResponseDto();
        dto.setGeometry(mapper.toGeoJson(result.coordinates()));
        dto.setDistanceKm(result.distanceKm());
        return ResponseEntity.ok(dto);
    }
}
