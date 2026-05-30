package eu.cokeman.velomarker.out.persistence.jpa.repository;

import eu.cokeman.velomarker.out.persistence.jpa.entity.RouteDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteDraftJpaRepository extends JpaRepository<RouteDraftEntity, UUID> {

    List<RouteDraftEntity> findAllByUserIdOrderByUpdatedAtDesc(UUID userId);

    List<RouteDraftEntity> findAllByUserIdAndGroupIdOrderByDayNumberAsc(UUID userId, UUID groupId);

    Optional<RouteDraftEntity> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);

    @Query("SELECT COUNT(d) > 0 FROM RouteDraftEntity d " +
            "WHERE d.userId = :userId AND d.name = :name AND d.id <> :excludingId")
    boolean existsByUserIdAndNameExcludingId(@Param("userId") UUID userId,
                                             @Param("name") String name,
                                             @Param("excludingId") UUID excludingId);

    @Modifying
    @Query("DELETE FROM RouteDraftEntity d WHERE d.id = :id AND d.userId = :userId")
    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}
