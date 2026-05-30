package velomarker.entity.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root sesji asystenta. JEDNA sesja na użytkownika (constraint UNIQUE na user_id w DB).
 * Zmiana intentu / „Zacznij od nowa" = nowy stan tej samej sesji (preferencje wyczyszczone,
 * powiązane PlanningSessionDay usunięte ON DELETE CASCADE).
 *
 * <p>Dni przechowywane w osobnej tabeli (PlanningSessionDay) — nie inlinujemy ich w session
 * żeby umożliwić atomowe update'y per-dzień (edycja waypointów dnia X bez przepisywania reszty).
 */
public final class PlanningSession {

    private final UUID id;
    private final UUID userId;
    private PlanningIntent intent;
    private RoutePreferences preferences;
    private UUID lastTaskId;
    private PlanningSummary summary;
    private final Instant createdAt;
    private Instant updatedAt;

    public PlanningSession(UUID id, UUID userId, PlanningIntent intent, RoutePreferences preferences,
                           UUID lastTaskId, PlanningSummary summary,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.intent = intent;
        this.preferences = preferences != null ? preferences : RoutePreferences.empty();
        this.lastTaskId = lastTaskId;
        this.summary = summary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Nowa sesja — pusty stan (intent=null, brak preferences). */
    public static PlanningSession freshFor(UUID userId) {
        Instant now = Instant.now();
        return new PlanningSession(UUID.randomUUID(), userId, null, RoutePreferences.empty(), null, null, now, now);
    }

    /** Zmiana intentu zeruje preferencje (zmiana intentu = nowa wyprawa). */
    public void setIntent(PlanningIntent newIntent) {
        this.intent = newIntent;
        this.preferences = RoutePreferences.empty();
        this.lastTaskId = null;
        this.summary = null;
        touch();
    }

    /** Częściowy update preferencji (PATCH semantyka — merge nie-null pól). */
    public void mergePreferences(RoutePreferences delta) {
        this.preferences = this.preferences.mergedWith(delta);
        touch();
    }

    /** Pełne podmienienie preferencji (np. po reset). */
    public void replacePreferences(RoutePreferences newPreferences) {
        this.preferences = newPreferences != null ? newPreferences : RoutePreferences.empty();
        touch();
    }

    public void setLastTaskId(UUID taskId) {
        this.lastTaskId = taskId;
        touch();
    }

    public void setSummary(PlanningSummary newSummary) {
        this.summary = newSummary;
        touch();
    }

    /** Reset preferences + intent + lastTaskId (zacznij od nowa). */
    public void reset() {
        this.intent = null;
        this.preferences = RoutePreferences.empty();
        this.lastTaskId = null;
        this.summary = null;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public PlanningIntent intent() { return intent; }
    public RoutePreferences preferences() { return preferences; }
    public UUID lastTaskId() { return lastTaskId; }
    public PlanningSummary summary() { return summary; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
