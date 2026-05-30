package eu.cokeman.velomarker.out.persistence.jpa.repository;

import eu.cokeman.velomarker.mapper.RouteDraftJpaMapper;
import eu.cokeman.velomarker.out.persistence.jpa.entity.RouteDraftEntity;
import org.springframework.transaction.annotation.Transactional;
import velomarker.entity.RouteDraft;
import velomarker.port.out.RouteDraftRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RouteDraftRepositoryImpl implements RouteDraftRepository {

    private final RouteDraftJpaRepository jpa;
    private final RouteDraftJpaMapper mapper;

    public RouteDraftRepositoryImpl(RouteDraftJpaRepository jpa, RouteDraftJpaMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RouteDraft save(RouteDraft draft) {
        RouteDraftEntity entity = mapper.toEntity(draft);
        RouteDraftEntity saved = jpa.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public List<RouteDraft> findAllByUserId(UUID userId) {
        return jpa.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<RouteDraft> findAllByUserIdAndGroupId(UUID userId, UUID groupId) {
        return jpa.findAllByUserIdAndGroupIdOrderByDayNumberAsc(userId, groupId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Optional<RouteDraft> findByIdAndUserId(UUID draftId, UUID userId) {
        return jpa.findByIdAndUserId(draftId, userId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByUserIdAndName(UUID userId, String name) {
        return jpa.existsByUserIdAndName(userId, name);
    }

    @Override
    public boolean existsByUserIdAndNameExcludingId(UUID userId, String name, UUID excludingId) {
        return jpa.existsByUserIdAndNameExcludingId(userId, name, excludingId);
    }

    @Override
    @Transactional
    public boolean deleteByIdAndUserId(UUID draftId, UUID userId) {
        return jpa.deleteByIdAndUserId(draftId, userId) > 0;
    }
}
