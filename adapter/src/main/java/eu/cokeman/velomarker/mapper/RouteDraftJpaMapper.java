package eu.cokeman.velomarker.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.cokeman.velomarker.out.persistence.jpa.entity.RouteDraftEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import velomarker.entity.RouteDraft;
import velomarker.entity.RouteStats;

@Component
public class RouteDraftJpaMapper {

    private static final Logger log = LoggerFactory.getLogger(RouteDraftJpaMapper.class);

    private final ObjectMapper json = new ObjectMapper();

    public RouteDraftEntity toEntity(RouteDraft draft) {
        RouteDraftEntity e = new RouteDraftEntity();
        e.setId(draft.id());
        e.setUserId(draft.userId());
        e.setName(draft.name());
        e.setGeometry(Polyline3DCodec.encode(draft.coordinates()));   // ślad 3D → zwarty string (kompresja w bazie)
        e.setProfile(draft.profile());
        e.setDistanceKm(draft.distanceKm());
        e.setElevationGain(draft.elevationGain());
        e.setElevationLoss(draft.elevationLoss());
        e.setGroupId(draft.groupId());
        e.setGroupName(draft.groupName());
        e.setDayNumber(draft.dayNumber());
        e.setWaypoints(draft.waypointsEncoded());
        e.setStatsJson(serializeStats(draft.stats()));
        e.setCreatedAt(draft.createdAt());
        e.setUpdatedAt(draft.updatedAt());
        return e;
    }

    public RouteDraft toDomain(RouteDraftEntity e) {
        return new RouteDraft(
                e.getId(),
                e.getUserId(),
                e.getName(),
                Polyline3DCodec.decode(e.getGeometry()),
                e.getProfile(),
                e.getDistanceKm(),
                e.getElevationGain(),
                e.getElevationLoss(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getGroupId(),
                e.getGroupName(),
                e.getDayNumber(),
                e.getWaypoints(),
                deserializeStats(e.getStatsJson())
        );
    }

    private String serializeStats(RouteStats stats) {
        if (stats == null || stats.totalMeters() == 0) return null;
        try {
            return json.writeValueAsString(stats);
        } catch (Exception ex) {
            log.warn("Failed to serialize RouteStats to JSON (skipping persist): {}", ex.getMessage());
            return null;
        }
    }

    private RouteStats deserializeStats(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) return null;
        try {
            return json.readValue(statsJson, RouteStats.class);
        } catch (Exception ex) {
            log.warn("Failed to deserialize RouteStats from JSON (treating as null): {}", ex.getMessage());
            return null;
        }
    }
}
