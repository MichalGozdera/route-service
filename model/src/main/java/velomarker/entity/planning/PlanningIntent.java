package velomarker.entity.planning;

/**
 * Intent użytkownika dla asystenta tras. Wybierany jawnie na początku (chipy w UI).
 * Zmiana intentu = reset preferencji i policzonych dni.
 *
 * <ul>
 *   <li>{@link #COVERAGE} — zbieranie nieodwiedzonych obszarów (Kraje + Poziomy administracyjne).</li>
 *   <li>{@link #AB} — trasa z punktu A do punktu B (opcjonalne viapointy).</li>
 *   <li>{@link #FREESTYLE} — swobodna jazda w regionie (pętla z bazy lub A→B bez celu zaliczania).</li>
 * </ul>
 */
public enum PlanningIntent {
    COVERAGE,
    AB,
    FREESTYLE
}
