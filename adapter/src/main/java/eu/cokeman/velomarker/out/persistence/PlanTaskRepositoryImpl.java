package eu.cokeman.velomarker.out.persistence;

import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanTaskEntity;
import eu.cokeman.velomarker.out.persistence.jpa.repository.PlanTaskJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import velomarker.entity.planning.PlanTask;
import velomarker.entity.planning.PlanTaskStatus;
import velomarker.port.out.planning.PlanTaskRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanTaskRepositoryImpl implements PlanTaskRepository {

    private final PlanTaskJpaRepository jpaRepository;

    public PlanTaskRepositoryImpl(PlanTaskJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public PlanTask save(PlanTask task) {
        PlanTaskEntity entity = jpaRepository.findById(task.id()).orElseGet(PlanTaskEntity::new);
        entity.setId(task.id());
        entity.setSessionId(task.sessionId());
        entity.setUserId(task.userId());
        entity.setStatus(task.status().name());
        entity.setPhase(task.phase());
        entity.setProgressCurrent(task.progressCurrent());
        entity.setProgressTotal(task.progressTotal());
        entity.setError(task.error());
        entity.setStartedAt(task.startedAt());
        entity.setCompletedAt(task.completedAt());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanTask> findById(UUID taskId) {
        return jpaRepository.findById(taskId).map(PlanTaskRepositoryImpl::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanTask> findLatestForUser(UUID userId) {
        return jpaRepository.findFirstByUserIdOrderByStartedAtDesc(userId).map(PlanTaskRepositoryImpl::toDomain);
    }

    @Override
    @Transactional
    public void deleteBySessionId(UUID sessionId) {
        jpaRepository.deleteBySessionId(sessionId);
    }

    private static PlanTask toDomain(PlanTaskEntity e) {
        return new PlanTask(e.getId(), e.getSessionId(), e.getUserId(),
                PlanTaskStatus.valueOf(e.getStatus()), e.getPhase(),
                e.getProgressCurrent(), e.getProgressTotal(), e.getError(),
                e.getStartedAt(), e.getCompletedAt());
    }
}
