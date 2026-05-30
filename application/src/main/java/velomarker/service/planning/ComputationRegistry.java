package velomarker.service.planning;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejestr stanu liczenia tras per-task — pozwala ANULOWAĆ liczenie z innego żądania/wątku.
 *
 * <p>{@code computing} = taski aktualnie budujące plan (ciężka praca: visit-service + brouter + elevation).
 * {@code cancelRequested} = poproszono o anulowanie; wątek liczący sprawdza flagę MIĘDZY kawałkami/fazami i przerywa.
 *
 * <p>Thread-safe (concurrent set): endpoint cancel (wątek B) ustawia flagę, wątek liczący (A) ją czyta.
 *
 * <p>Przeniesione z assistant-service. W route-service kluczem jest task databaseId (UUID) zamiast
 * UUID konwersacji — zgodnie z common-application/async semantyką.
 */
public final class ComputationRegistry {

    private final Set<UUID> computing = ConcurrentHashMap.newKeySet();
    private final Set<UUID> cancelRequested = ConcurrentHashMap.newKeySet();

    /** Start liczenia: oznacz jako computing i wyczyść ewentualne stare żądanie anulowania. */
    public void begin(UUID taskId) {
        if (taskId != null) {
            cancelRequested.remove(taskId);
            computing.add(taskId);
        }
    }

    /** Koniec liczenia (sukces/błąd/anulowanie) — sprzątnij oba znaczniki. */
    public void end(UUID taskId) {
        if (taskId != null) {
            computing.remove(taskId);
            cancelRequested.remove(taskId);
        }
    }

    public boolean isComputing(UUID taskId) {
        return taskId != null && computing.contains(taskId);
    }

    /** Z innego żądania: poproś o przerwanie trwającego liczenia tego taska. */
    public void requestCancel(UUID taskId) {
        if (taskId != null) {
            cancelRequested.add(taskId);
        }
    }

    public boolean isCancelRequested(UUID taskId) {
        return taskId != null && cancelRequested.contains(taskId);
    }

    public void clearCancel(UUID taskId) {
        if (taskId != null) {
            cancelRequested.remove(taskId);
        }
    }
}
