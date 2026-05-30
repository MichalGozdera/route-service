package velomarker.entity.planning;

/**
 * Podsumowanie policzonej wyprawy.
 *
 * @param totalDistanceKm     suma policzonych dni z BRouter (km)
 * @param totalElevationGain  suma wzniosu (z elevation samplera, m)
 * @param budgetKm            days × kmPerDay (z preferences w momencie liczenia)
 * @param verdict             OK / SURPLUS / DEFICIT / BUDGET_IMPOSSIBLE z BudgetReconciler
 * @param surplusKm           dla SURPLUS: nadwyżka km; inaczej 0
 * @param poolSize            ile obszarów po reconcile (finalna pula)
 * @param initialPoolSize     ile obszarów PRZED reconcile (cap MAX_TSP_AREAS lub mniej)
 * @param reconcileIters      ile iteracji pętli reconcile wykonano
 * @param reconcileTrims      ile razy `trim`
 * @param reconcileGrows      ile razy `grow`
 * @param baselineKm          baseline BRouter (start→via→meta, bez obszarów) — null gdy nieliczone (AB/FREESTYLE)
 * @param roadAnchors         calibrator.roadAnchors() po baseline probe — null gdy nieliczone
 * @param roadAreas           calibrator.roadAreas() po areas probe — null gdy nieliczone
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
        int reconcileIters,
        int reconcileTrims,
        int reconcileGrows,
        Double baselineKm,
        Double roadAnchors,
        Double roadAreas,
        boolean climbWarning
) {
    public enum BudgetVerdict { OK, SURPLUS, DEFICIT, BUDGET_IMPOSSIBLE }

    /** Skrócony konstruktor dla scenariuszy bez baseline (AB/FREESTYLE). */
    public static PlanningSummary simple(double totalDistanceKm, int totalElevationGain,
                                         int budgetKm, BudgetVerdict verdict, int surplusKm) {
        return new PlanningSummary(totalDistanceKm, totalElevationGain, budgetKm,
                verdict, surplusKm, 0, 0, 0, 0, 0, null, null, null, false);
    }
}
