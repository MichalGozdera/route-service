package velomarker.entity.planning;

/**
 * Podsumowanie policzonej wyprawy.
 *
 * @param totalDistanceKm     suma policzonych dni z BRouter (km)
 * @param totalElevationGain  suma wzniosu (z elevation samplera, m)
 * @param budgetKm            days × kmPerDay (z preferences w momencie liczenia)
 * @param verdict             OK / SURPLUS / DEFICIT / BUDGET_IMPOSSIBLE z BudgetReconciler
 * @param surplusKm           dla SURPLUS: nadwyżka km; inaczej 0
 * @param poolSize            wybrana pula obszarów (finalna)
 * @param initialPoolSize     pula kandydatów po scoringu (przed wyborem)
 * @param baselineKm          baseline BRouter (start→via→meta, bez obszarów) — null gdy nieliczone (AB/FREESTYLE)
 * @param climbWarning        true gdy totalElevationGain > refClimbTotal × 1.10 (user-facing warning)
 */
public record PlanningSummary(
        double totalDistanceKm,
        int totalElevationGain,
        int budgetKm,
        BudgetVerdict verdict,
        int surplusKm,
        int poolSize,
        int initialPoolSize,
        Double baselineKm,
        boolean climbWarning
) {
    public enum BudgetVerdict { OK, SURPLUS, DEFICIT, BUDGET_IMPOSSIBLE }

}
