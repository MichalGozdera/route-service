package velomarker.service.planning;

import velomarker.entity.planning.ManualSession;
import velomarker.port.in.planning.ManualSessionUseCase;
import velomarker.port.out.planning.ManualSessionRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Czyste CRUD na trasie manualnej. Front przysyła gotową geometrię 3D (z wysokością) + gain/loss/stats
 * z policzonego profilu — backend nie próbkuje DEM ani nie woła BRoutera, tylko przechowuje.
 */
public class ManualSessionService implements ManualSessionUseCase {

    private final ManualSessionRepository repository;

    public ManualSessionService(ManualSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ManualSession> get(UUID userId) {
        return repository.findByUserId(userId);
    }

    @Override
    public ManualSession save(ManualSession session) {
        return repository.save(session);
    }

    @Override
    public void delete(UUID userId) {
        repository.deleteByUserId(userId);
    }
}
