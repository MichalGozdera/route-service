package eu.cokeman.velomarker.out.persistence.jpa.repository;

import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanningSessionDayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlanningSessionDayJpaRepository extends JpaRepository<PlanningSessionDayEntity, UUID> {
    List<PlanningSessionDayEntity> findBySessionIdOrderByDayNumberAsc(UUID sessionId);

    Optional<PlanningSessionDayEntity> findBySessionIdAndDayNumber(UUID sessionId, int dayNumber);

    void deleteBySessionId(UUID sessionId);
}
