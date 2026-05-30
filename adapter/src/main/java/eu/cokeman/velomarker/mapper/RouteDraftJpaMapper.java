package eu.cokeman.velomarker.mapper;

import eu.cokeman.velomarker.out.persistence.jpa.entity.RouteDraftEntity;
import org.springframework.stereotype.Component;
import velomarker.entity.RouteDraft;

import java.util.List;

@Component
public class RouteDraftJpaMapper {

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
                e.getWaypoints()
        );
    }
}
