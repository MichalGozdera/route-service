package eu.cokeman.velomarker.mapper;

import eu.cokeman.velomarker.openapi.model.ManualSessionResponseDto;
import eu.cokeman.velomarker.openapi.model.ManualSessionUpsertRequestDto;
import eu.cokeman.velomarker.openapi.model.WaypointDto;
import org.springframework.stereotype.Component;
import velomarker.entity.planning.ManualSession;
import velomarker.entity.planning.Waypoint;

import java.util.List;
import java.util.UUID;

/** Mapowanie Domain ↔ REST DTO dla trasy manualnej. Geometria 3D przez Polyline3DCodec, stats reuse. */
@Component
public class ManualSessionExternalMapper {

    private final RouteDraftExternalMapper statsMapper;

    public ManualSessionExternalMapper(RouteDraftExternalMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    public ManualSessionResponseDto toResponse(ManualSession m) {
        ManualSessionResponseDto dto = new ManualSessionResponseDto();
        dto.setId(m.id());
        dto.setUserId(m.userId());
        dto.setGeometryEncoded(Polyline3DCodec.encode(m.geometry()));
        dto.setWaypoints(m.waypoints().stream().map(this::toDto).toList());
        dto.setProfile(m.profile());
        dto.setEditedAt(m.editedAt());
        if (m.distanceKm() != null) dto.setDistanceKm(m.distanceKm());
        if (m.elevationGain() != null) dto.setElevationGain(m.elevationGain());
        if (m.elevationLoss() != null) dto.setElevationLoss(m.elevationLoss());
        dto.setStats(statsMapper.toStatsDto(m.stats()));
        return dto;
    }

    public ManualSession fromUpsert(UUID userId, ManualSessionUpsertRequestDto req) {
        List<double[]> geometry = Polyline3DCodec.decode(req.getGeometryEncoded());
        List<Waypoint> waypoints = req.getWaypoints() == null ? List.of()
                : req.getWaypoints().stream().map(this::fromDto).toList();
        return ManualSession.create(userId, geometry, waypoints,
                req.getDistanceKm(), req.getElevationGain(), req.getElevationLoss(),
                req.getProfile(), statsMapper.fromStatsDto(req.getStats()));
    }

    private WaypointDto toDto(Waypoint w) {
        WaypointDto dto = new WaypointDto();
        dto.setLng(w.lng());
        dto.setLat(w.lat());
        if (w.name() != null) dto.setName(w.name());
        return dto;
    }

    private Waypoint fromDto(WaypointDto dto) {
        return new Waypoint(dto.getLng(), dto.getLat(), dto.getName());
    }
}
