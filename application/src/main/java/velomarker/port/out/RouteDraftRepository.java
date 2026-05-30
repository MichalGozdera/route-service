package velomarker.port.out;

import velomarker.entity.RouteDraft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteDraftRepository {

    RouteDraft save(RouteDraft draft);

    List<RouteDraft> findAllByUserId(UUID userId);

    /** Dni jednej wyprawy (grupy) usera, posortowane po dayNumber. Pusta lista gdy brak. */
    List<RouteDraft> findAllByUserIdAndGroupId(UUID userId, UUID groupId);

    Optional<RouteDraft> findByIdAndUserId(UUID draftId, UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);

    boolean existsByUserIdAndNameExcludingId(UUID userId, String name, UUID excludingId);

    boolean deleteByIdAndUserId(UUID draftId, UUID userId);
}
