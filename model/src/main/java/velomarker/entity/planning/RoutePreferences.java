package velomarker.entity.planning;

import java.util.List;

/**
 * Preferencje formularza asystenta. Pola nullable — formularz może być częściowo wypełniony,
 * walidacja przed liczeniem trasy dzieje się w PlanningOrchestrationService.
 *
 * <p>Intent (COVERAGE/AB/FREESTYLE) trzymany osobno w {@link PlanningSession} — tutaj tylko
 * pola wypełniane przez user'a w formularzu. Zmiana intentu w sesji = nowy obiekt preferencji.
 */
public record RoutePreferences(
        List<Integer> countryIds,        // COVERAGE: kraje wybrane checkboxami
        List<Integer> levelIds,          // COVERAGE: poziomy administracyjne (multi-target)
        List<Integer> specialGroupIds,   // COVERAGE: grupy specjalne (opcjonalnie)
        Waypoint start,                  // AB/FREESTYLE: punkt początkowy (opcjonalny dla FREESTYLE)
        Waypoint end,                    // AB: punkt końcowy
        List<Waypoint> via,              // AB/COVERAGE: punkty pośrednie (chipy w UI)
        Boolean loop,                    // pętla (wróć do startu) — wyklucza się z `end`
        Integer days,
        Integer kmPerDay,
        Integer elevationPerDayM,
        String profile,                  // jawny profil BRouter (fastbike/trekking/safety/fastbike-lowtraffic)
        RouteStyle style,                // opcjonalny, gdy brak `profile`
        Tempo tempo                      // opcjonalny, ma priorytet nad `style` przy mapowaniu na profil
) {

    public static RoutePreferences empty() {
        return new RoutePreferences(List.of(), List.of(), List.of(), null, null, List.of(),
                null, null, null, null, null, null, null);
    }

    /**
     * Nadpisuje pola wartościami nie-null z {@code o} (PATCH semantyka).
     * Listy zastępują w całości (gdy {@code o.list} nie-null — nawet jeśli pusta).
     */
    public RoutePreferences mergedWith(RoutePreferences o) {
        if (o == null) return this;
        return new RoutePreferences(
                o.countryIds != null ? o.countryIds : countryIds,
                o.levelIds != null ? o.levelIds : levelIds,
                o.specialGroupIds != null ? o.specialGroupIds : specialGroupIds,
                o.start != null ? o.start : start,
                o.end != null ? o.end : end,
                o.via != null ? o.via : via,
                o.loop != null ? o.loop : loop,
                o.days != null ? o.days : days,
                o.kmPerDay != null ? o.kmPerDay : kmPerDay,
                o.elevationPerDayM != null ? o.elevationPerDayM : elevationPerDayM,
                o.profile != null ? o.profile : profile,
                o.style != null ? o.style : style,
                o.tempo != null ? o.tempo : tempo
        );
    }

    /** Czy formularz ma minimum potrzebne do uruchomienia liczenia dla danego intentu. */
    public boolean isReadyToCalculate(PlanningIntent intent) {
        if (intent == null) return false;
        return switch (intent) {
            case COVERAGE -> countryIds != null && !countryIds.isEmpty()
                    && (levelIds != null && !levelIds.isEmpty() || specialGroupIds != null && !specialGroupIds.isEmpty())
                    && start != null
                    && (loop == Boolean.TRUE || end != null)
                    && days != null && days > 0
                    && kmPerDay != null && kmPerDay > 0;
            case AB -> start != null && end != null
                    && days != null && days > 0
                    && kmPerDay != null && kmPerDay > 0;
            case FREESTYLE -> start != null
                    && (loop == Boolean.TRUE || end != null)
                    && days != null && days > 0
                    && kmPerDay != null && kmPerDay > 0;
        };
    }
}
