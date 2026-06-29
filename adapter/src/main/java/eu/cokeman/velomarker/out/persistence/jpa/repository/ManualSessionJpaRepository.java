package eu.cokeman.velomarker.out.persistence.jpa.repository;

import eu.cokeman.velomarker.out.persistence.jpa.entity.ManualSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ManualSessionJpaRepository extends JpaRepository<ManualSessionEntity, UUID> {
    Optional<ManualSessionEntity> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
