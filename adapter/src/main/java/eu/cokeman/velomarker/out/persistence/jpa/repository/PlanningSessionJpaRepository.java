package eu.cokeman.velomarker.out.persistence.jpa.repository;

import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanningSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlanningSessionJpaRepository extends JpaRepository<PlanningSessionEntity, UUID> {
    Optional<PlanningSessionEntity> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
