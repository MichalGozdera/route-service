package velomarker.port.in.planning;

import velomarker.entity.planning.PlanningIntent;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.RoutePreferences;

import java.util.Optional;
import java.util.UUID;

/**
 * CRUD na sesji asystenta. JEDEN aktywny stan / user. Zmiana intentu lub reset zeruje preferences
 * i kasuje policzone dni (CASCADE DELETE w DB).
 */
public interface PlanningSessionUseCase {

    /** Aktualny stan sesji dla użytkownika; pusty Optional gdy user nigdy nie wszedł w asystenta. */
    Optional<PlanningSession> getSession(UUID userId);

    /** Ustawia/zmienia intent. Tworzy sesję jeśli nie istnieje. Zmiana intentu = zerowanie preferences + days. */
    PlanningSession setIntent(UUID userId, PlanningIntent intent);

    /** Częściowy update preferences (PATCH semantyka — pola nie-null są nadpisywane). */
    PlanningSession updateForm(UUID userId, RoutePreferences delta);

    /** Reset („Zacznij od nowa") — intent=null, preferences=empty, days usunięte. */
    void reset(UUID userId);
}
