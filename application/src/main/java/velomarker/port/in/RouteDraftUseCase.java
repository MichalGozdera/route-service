package velomarker.port.in;

import velomarker.entity.RouteDraft;

import java.util.List;
import java.util.UUID;

public interface RouteDraftUseCase {

    RouteDraft create(RouteDraftCreateCommand command);

    List<RouteDraft> listForUser(UUID userId);

    RouteDraft getForUser(UUID userId, UUID draftId);

    /** Wszystkie dni jednej wyprawy (grupy), posortowane po dayNumber. Pusta lista gdy brak. */
    List<RouteDraft> getGroupForUser(UUID userId, UUID groupId);

    RouteDraft update(RouteDraftUpdateCommand command);

    void delete(UUID userId, UUID draftId);

    record RouteDraftCreateCommand(
            UUID userId,
            String name,
            List<double[]> coordinates,
            String profile,
            Double distanceKm,
            Integer elevationGain,
            Integer elevationLoss,
            UUID groupId,
            String groupName,
            Integer dayNumber,
            String waypointsEncoded
    ) {
    }

    record RouteDraftUpdateCommand(
            UUID userId,
            UUID draftId,
            String name,
            List<double[]> coordinates,
            String profile,
            Double distanceKm,
            Integer elevationGain,
            Integer elevationLoss,
            UUID groupId,
            String groupName,
            Integer dayNumber,
            String waypointsEncoded
    ) {
    }
}
