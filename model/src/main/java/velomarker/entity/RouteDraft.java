package velomarker.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteDraft(
        UUID id,
        UUID userId,
        String name,
        List<double[]> coordinates,
        String profile,
        Double distanceKm,
        Integer elevationGain,
        Integer elevationLoss,
        Instant createdAt,
        Instant updatedAt,
        // Grupowanie w „wyprawy": dni jednej wyprawy mają wspólne groupId/groupName + numer dnia.
        UUID groupId,
        String groupName,
        Integer dayNumber,
        // Waypointy (gminy) jako zakodowana polyline (OPAQUE) — pozwala wczytać szkic edytowalnie.
        String waypointsEncoded
) {
}
