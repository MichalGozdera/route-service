package eu.cokeman.velomarker.out.persistence;

import eu.cokeman.velomarker.mapper.PlanningJpaMapper;
import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanningSessionEntity;
import eu.cokeman.velomarker.out.persistence.jpa.repository.PlanningSessionJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import velomarker.entity.planning.PlanningSession;
import velomarker.port.out.planning.PlanningSessionRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanningSessionRepositoryImpl implements PlanningSessionRepository {

    private final PlanningSessionJpaRepository jpaRepository;
    private final PlanningJpaMapper mapper;

    public PlanningSessionRepositoryImpl(PlanningSessionJpaRepository jpaRepository, PlanningJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanningSession> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public PlanningSession save(PlanningSession session) {
        PlanningSessionEntity existing = jpaRepository.findByUserId(session.userId()).orElse(null);
        if (existing != null) {
            mapper.applyTo(session, existing);
            PlanningSessionEntity saved = jpaRepository.save(existing);
            return mapper.toDomain(saved);
        }
        PlanningSessionEntity entity = mapper.toEntity(session);
        PlanningSessionEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional
    public void deleteByUserId(UUID userId) {
        jpaRepository.deleteByUserId(userId);
    }
}
