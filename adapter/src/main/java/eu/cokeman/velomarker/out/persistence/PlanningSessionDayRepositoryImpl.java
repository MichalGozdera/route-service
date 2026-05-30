package eu.cokeman.velomarker.out.persistence;

import eu.cokeman.velomarker.mapper.PlanningJpaMapper;
import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanningSessionDayEntity;
import eu.cokeman.velomarker.out.persistence.jpa.repository.PlanningSessionDayJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.port.out.planning.PlanningSessionDayRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanningSessionDayRepositoryImpl implements PlanningSessionDayRepository {

    private final PlanningSessionDayJpaRepository jpaRepository;
    private final PlanningJpaMapper mapper;

    public PlanningSessionDayRepositoryImpl(PlanningSessionDayJpaRepository jpaRepository,
                                            PlanningJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanningSessionDay> findBySessionId(UUID sessionId) {
        return jpaRepository.findBySessionIdOrderByDayNumberAsc(sessionId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanningSessionDay> findBySessionIdAndDayNumber(UUID sessionId, int dayNumber) {
        return jpaRepository.findBySessionIdAndDayNumber(sessionId, dayNumber).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public PlanningSessionDay save(PlanningSessionDay day) {
        PlanningSessionDayEntity existing = jpaRepository
                .findBySessionIdAndDayNumber(day.sessionId(), day.dayNumber())
                .orElse(null);
        PlanningSessionDayEntity entity;
        if (existing != null) {
            existing.setGeometry(mapper.toEntity(day).getGeometry());
            existing.setWaypoints(mapper.toEntity(day).getWaypoints());
            existing.setDistanceKm(day.distanceKm());
            existing.setElevationGain(day.elevationGain());
            existing.setElevationLoss(day.elevationLoss());
            existing.setProfile(day.profile());
            existing.setEditedAt(day.editedAt());
            entity = existing;
        } else {
            entity = mapper.toEntity(day);
        }
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional
    public void replaceAll(UUID sessionId, List<PlanningSessionDay> days) {
        // Hibernate w jednej transakcji potrafi reorderować i wykonać INSERTy przed DELETE,
        // przez co unique (session_id, day_number) wybucha gdy „Policz trasę" puszczamy ponownie.
        // Flush wymusza DELETE do DB przed kolejnymi insertami.
        jpaRepository.deleteBySessionId(sessionId);
        jpaRepository.flush();
        List<PlanningSessionDayEntity> entities = days.stream().map(mapper::toEntity).toList();
        jpaRepository.saveAll(entities);
    }

    @Override
    @Transactional
    public void deleteBySessionId(UUID sessionId) {
        jpaRepository.deleteBySessionId(sessionId);
    }
}
