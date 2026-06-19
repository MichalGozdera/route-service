package eu.cokeman.velomarker.mapper;

import eu.cokeman.velomarker.openapi.model.LineStringGeoJsonDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftRequestDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftResponseDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftSummaryDto;
import eu.cokeman.velomarker.openapi.model.RouteSpanDto;
import eu.cokeman.velomarker.openapi.model.RouteStatsDto;
import org.springframework.stereotype.Component;
import velomarker.entity.RouteDraft;
import velomarker.entity.RouteSpan;
import velomarker.entity.RouteStats;

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
        dto.setStats(toStatsDto(d.stats()));
        dto.setCreatedAt(d.createdAt());
        dto.setUpdatedAt(d.updatedAt());
        return dto;
    }

    public RouteStatsDto toStatsDto(RouteStats stats) {
        if (stats == null) return null;
        RouteStatsDto dto = new RouteStatsDto();
        dto.setTotalMeters(stats.totalMeters());
        dto.setSurfaceMeters(stats.surfaceMeters());
        dto.setRoadMeters(stats.roadMeters());
        dto.setSmoothnessMeters(stats.smoothnessMeters());
        dto.setSurfaceSpans(toSpansDto(stats.surfaceSpans()));
        dto.setRoadSpans(toSpansDto(stats.roadSpans()));
        dto.setSmoothnessSpans(toSpansDto(stats.smoothnessSpans()));
        return dto;
    }

    public RouteStats fromStatsDto(RouteStatsDto dto) {
        if (dto == null) return null;
        return new RouteStats(
                dto.getTotalMeters() == null ? 0 : dto.getTotalMeters(),
                dto.getSurfaceMeters() == null ? java.util.Map.of() : dto.getSurfaceMeters(),
                dto.getRoadMeters() == null ? java.util.Map.of() : dto.getRoadMeters(),
                dto.getSmoothnessMeters() == null ? java.util.Map.of() : dto.getSmoothnessMeters(),
                fromSpansDto(dto.getSurfaceSpans()),
                fromSpansDto(dto.getRoadSpans()),
                fromSpansDto(dto.getSmoothnessSpans()));
    }

    private static List<RouteSpanDto> toSpansDto(List<RouteSpan> spans) {
        if (spans == null || spans.isEmpty()) return List.of();
        List<RouteSpanDto> out = new ArrayList<>(spans.size());
        for (RouteSpan s : spans) {
            RouteSpanDto d = new RouteSpanDto();
            d.setStartIdx(s.startIdx());
            d.setEndIdx(s.endIdx());
            d.setCode(s.code());
            out.add(d);
        }
        return out;
    }

    private static List<RouteSpan> fromSpansDto(List<RouteSpanDto> spans) {
        if (spans == null || spans.isEmpty()) return List.of();
        List<RouteSpan> out = new ArrayList<>(spans.size());
        for (RouteSpanDto s : spans) {
            out.add(new RouteSpan(
                    s.getStartIdx() == null ? 0 : s.getStartIdx(),
                    s.getEndIdx() == null ? 0 : s.getEndIdx(),
                    s.getCode()));
        }
        return out;
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
