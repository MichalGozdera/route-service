package eu.cokeman.velomarker.out.persistence.jpa.repository;

import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlanTaskJpaRepository extends JpaRepository<PlanTaskEntity, UUID> {

    Optional<PlanTaskEntity> findFirstByUserIdOrderByStartedAtDesc(UUID userId);

    void deleteBySessionId(UUID sessionId);
}
