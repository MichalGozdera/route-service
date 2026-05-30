package eu.cokeman.velomarker.mapper;

import eu.cokeman.velomarker.openapi.model.LineStringGeoJsonDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftRequestDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftResponseDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftSummaryDto;
import org.springframework.stereotype.Component;
import velomarker.entity.RouteDraft;

import java.util.ArrayList;
import java.util.List;

@Component
public class RouteDraftExternalMapper {

    public RouteDraftResponseDto toResponseDto(RouteDraft d) {
        RouteDraftResponseDto dto = new RouteDraftResponseDto();
        dto.setId(d.id());
        dto.setName(d.name());
        dto.setGeometry(toGeoJson(d.coordinates()));
        dto.setProfile(d.profile());
        dto.setDistanceKm(d.distanceKm());
        dto.setElevationGain(d.elevationGain());
        dto.setElevationLoss(d.elevationLoss());
        dto.setGroupId(d.groupId());
        dto.setGroupName(d.groupName());
        dto.setDayNumber(d.dayNumber());
        dto.setWaypoints(d.waypointsEncoded());
        dto.setCreatedAt(d.createdAt());
        dto.setUpdatedAt(d.updatedAt());
        return dto;
    }

    public RouteDraftSummaryDto toSummaryDto(RouteDraft d) {
        RouteDraftSummaryDto dto = new RouteDraftSummaryDto();
        dto.setId(d.id());
        dto.setName(d.name());
        dto.setProfile(d.profile());
        dto.setDistanceKm(d.distanceKm());
        dto.setElevationGain(d.elevationGain());
        dto.setElevationLoss(d.elevationLoss());
        dto.setGroupId(d.groupId());
        dto.setGroupName(d.groupName());
        dto.setDayNumber(d.dayNumber());
        dto.setCreatedAt(d.createdAt());
        dto.setUpdatedAt(d.updatedAt());
        return dto;
    }

    public LineStringGeoJsonDto toGeoJson(List<double[]> coords) {
        LineStringGeoJsonDto dto = new LineStringGeoJsonDto();
        dto.setType(LineStringGeoJsonDto.TypeEnum.LINE_STRING);
        List<List<Double>> out = new ArrayList<>(coords.size());
        for (double[] c : coords) {
            List<Double> point = new ArrayList<>(c.length);
            for (double v : c) point.add(v);
            out.add(point);
        }
        dto.setCoordinates(out);
        return dto;
    }

    public List<double[]> fromGeoJson(LineStringGeoJsonDto dto) {
        if (dto == null || dto.getCoordinates() == null) return List.of();
        List<double[]> out = new ArrayList<>(dto.getCoordinates().size());
        for (List<Double> p : dto.getCoordinates()) {
            double[] arr = new double[p.size()];
            for (int i = 0; i < p.size(); i++) arr[i] = p.get(i);
            out.add(arr);
        }
        return out;
    }

    public List<double[]> fromGeoJsonRequest(RouteDraftRequestDto req) {
        return fromGeoJson(req.getGeometry());
    }
}
