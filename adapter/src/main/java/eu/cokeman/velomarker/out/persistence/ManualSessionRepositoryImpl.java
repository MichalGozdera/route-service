package eu.cokeman.velomarker.out.persistence;

import eu.cokeman.velomarker.mapper.PlanningJpaMapper;
import eu.cokeman.velomarker.out.persistence.jpa.entity.ManualSessionEntity;
import eu.cokeman.velomarker.out.persistence.jpa.repository.ManualSessionJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import velomarker.entity.planning.ManualSession;
import velomarker.port.out.planning.ManualSessionRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ManualSessionRepositoryImpl implements ManualSessionRepository {

    private final ManualSessionJpaRepository jpaRepository;
    private final PlanningJpaMapper mapper;

    public ManualSessionRepositoryImpl(ManualSessionJpaRepository jpaRepository, PlanningJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ManualSession> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public ManualSession save(ManualSession session) {
        ManualSessionEntity existing = jpaRepository.findByUserId(session.userId()).orElse(null);
        if (existing != null) {
            mapper.applyTo(session, existing);
            return mapper.toDomain(jpaRepository.save(existing));
        }
        ManualSessionEntity entity = mapper.toEntity(session);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteByUserId(UUID userId) {
        jpaRepository.deleteByUserId(userId);
    }
}
