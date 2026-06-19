package velomarker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteDraft;
import velomarker.exception.RouteDraftNameDuplicateException;
import velomarker.exception.RouteDraftNotFoundException;
import velomarker.port.in.RouteDraftUseCase;
import velomarker.port.out.RouteDraftRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class RouteDraftManagementService implements RouteDraftUseCase {

    private static final Logger log = LoggerFactory.getLogger(RouteDraftManagementService.class);

    private final RouteDraftRepository repository;

    public RouteDraftManagementService(RouteDraftRepository repository) {
        this.repository = repository;
    }

    @Override
    public RouteDraft create(RouteDraftCreateCommand command) {
        validateName(command.name());
        if (repository.existsByUserIdAndName(command.userId(), command.name().trim())) {
            throw new RouteDraftNameDuplicateException(command.name());
        }
        Instant now = Instant.now();
        RouteDraft draft = new RouteDraft(
                UUID.randomUUID(),
                command.userId(),
                command.name().trim(),
                command.coordinates(),
                command.profile(),
                command.distanceKm(),
                command.elevationGain(),
                command.elevationLoss(),
                now,
                now,
                command.groupId(),
                command.groupName(),
                command.dayNumber(),
                command.waypointsEncoded(),
                command.stats()
        );
        log.info("Creating route draft userId={} name={}", command.userId(), draft.name());
        return repository.save(draft);
    }

    @Override
    public List<RouteDraft> listForUser(UUID userId) {
        return repository.findAllByUserId(userId);
    }

    @Override
    public RouteDraft getForUser(UUID userId, UUID draftId) {
        return repository.findByIdAndUserId(draftId, userId)
                .orElseThrow(() -> new RouteDraftNotFoundException(draftId));
    }

    @Override
    public List<RouteDraft> getGroupForUser(UUID userId, UUID groupId) {
        return repository.findAllByUserIdAndGroupId(userId, groupId);
    }

    @Override
    public RouteDraft update(RouteDraftUpdateCommand command) {
        validateName(command.name());
        RouteDraft existing = repository.findByIdAndUserId(command.draftId(), command.userId())
                .orElseThrow(() -> new RouteDraftNotFoundException(command.draftId()));

        String trimmedName = command.name().trim();
        if (!existing.name().equals(trimmedName)
                && repository.existsByUserIdAndNameExcludingId(command.userId(), trimmedName, command.draftId())) {
            throw new RouteDraftNameDuplicateException(trimmedName);
        }

        RouteDraft updated = new RouteDraft(
                existing.id(),
                existing.userId(),
                trimmedName,
                command.coordinates(),
                command.profile(),
                command.distanceKm(),
                command.elevationGain(),
                command.elevationLoss(),
                existing.createdAt(),
                Instant.now(),
                command.groupId() != null ? command.groupId() : existing.groupId(),
                command.groupName() != null ? command.groupName() : existing.groupName(),
                command.dayNumber() != null ? command.dayNumber() : existing.dayNumber(),
                command.waypointsEncoded() != null ? command.waypointsEncoded() : existing.waypointsEncoded(),
                command.stats() != null ? command.stats() : existing.stats()
        );
        log.info("Updating route draft id={} userId={}", existing.id(), existing.userId());
        return repository.save(updated);
    }

    @Override
    public void delete(UUID userId, UUID draftId) {
        if (!repository.deleteByIdAndUserId(draftId, userId)) {
            throw new RouteDraftNotFoundException(draftId);
        }
        log.info("Deleted route draft id={} userId={}", draftId, userId);
    }

    private void validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Draft name must not be blank");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("Draft name too long (max 255)");
        }
    }
}
