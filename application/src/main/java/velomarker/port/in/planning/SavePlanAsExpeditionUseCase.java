package velomarker.port.in.planning;

import java.util.List;
import java.util.UUID;

/**
 * Konwertuje wszystkie dni aktywnej sesji na route_draft z wspólnym group_id. Każdy dzień = osobny
 * RouteDraft (kompatybilny z istniejącym mechanizmem szkiców i widokiem „Wyprawy" w UI).
 */
public interface SavePlanAsExpeditionUseCase {

    /**
     * @param userId      właściciel sesji
     * @param groupName   nazwa wyprawy (prefix dla nazw dni: „{groupName} – Dzień N")
     */
    ExpeditionResult saveAsExpedition(UUID userId, String groupName);

    record ExpeditionResult(UUID groupId, List<UUID> draftIds) {
    }
}
