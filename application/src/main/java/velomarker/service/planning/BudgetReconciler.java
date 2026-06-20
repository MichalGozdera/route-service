package velomarker.service.planning;

/**
 * Pogodzenie trasy z budżetem (dni × km/dzień). Verdict TYLKO po km — climb to inna oś,
 * mierzymy go oddzielnie jako warning (patrz {@link PlanningSummary#climbWarning}).
 *
 * <ul>
 *   <li>{@link Verdict#BUDGET_IMPOSSIBLE} — sam baseline (start→via→meta bez obszarów) &gt; budżet×1.05.
 *       Algorytm NIE jest w stanie tej trasy zaplanować bez naruszenia budżetu user'a.</li>
 *   <li>{@link Verdict#DEFICIT} — realna trasa po reconcile dłuższa niż budżet+5% (powinno się
 *       konwergować bisekcją, ale gdy się nie udało).</li>
 *   <li>{@link Verdict#SURPLUS} — zostaje sensowny zapas (&gt; max(20 km, 10% budżetu)).</li>
 *   <li>{@link Verdict#OK} — w paśmie [budget×0.95, budget×1.05].</li>
 * </ul>
 */
public final class BudgetReconciler {

    private BudgetReconciler() {}

    public enum Verdict { OK, SURPLUS, DEFICIT, BUDGET_IMPOSSIBLE }

    public record Result(Verdict verdict, int budgetKm, int surplusKm) {}

    /**
     * Verdict uwzgledniajacy formule kary-nagrody: km overshoot jest USPRAWIEDLIWIONY gdy
     * wznios undershoot (1m mniej wzniosu = 0.0667 km extra dla szosy, 0.1 km dla offroad).
     * Trasa 2229 km / 16954 m climb przy budget 1800/21370 = -4400m climb -> +293 km usprawiedliwione.
     * Wzdłuż dopuszczalnej tolerancji ±5%, OK. Bez climb compensation: DEFICIT.
     */
    public static Result evaluateWithClimb(double routeKm, double routeClimbM,
                                            Integer days, Integer kmPerDay, Integer elevPerDay) {
        if (days == null || kmPerDay == null || days <= 0 || kmPerDay <= 0) {
            return new Result(Verdict.OK, 0, 0);
        }
        int budget = days * kmPerDay;
        int rk = (int) Math.round(routeKm);
        // Climb compensation: km usprawiedliwione przez climb undershoot. Współczynnik = Coverage ALPHA
        // (0.1 km/m), SPÓJNY z effort-modelem plannera (budget effort = km + 0.1×climb). Bez tego
        // verdict liczył luźniej (0.0667) niż planner → fałszywy DEFICIT mimo mieszczenia się w efforcie.
        double climbCompensationKm = 0;
        if (elevPerDay != null && elevPerDay > 0 && routeClimbM > 0) {
            int budgetClimb = days * elevPerDay;
            double climbUndershoot = Math.max(0, budgetClimb - routeClimbM);
            climbCompensationKm = climbUndershoot * 0.1; // 1 m wzniosu mniej = 0.1 km extra (= ALPHA)
        }
        double effectiveBudget = budget + climbCompensationKm;
        // Tolerancja 1.10 = sufit densify (planner CELOWO dopina donut-holes ponad budżet do 110%,
        // decyzja usera). Próg 1.05 piętnował tę intencjonalną nadwyżkę jako DEFICIT mimo że to
        // świadome wypełnianie dziur. 1.10 spójne z densifyCeiling w CoveragePlanner.
        if (rk > effectiveBudget * 1.10) {
            return new Result(Verdict.DEFICIT, budget, 0);
        }
        int surplus = budget - rk; // surplus relative to ORIGINAL budget (UI display)
        int threshold = Math.max(20, (int) Math.round(budget * 0.1));
        // OK jesli rk <= effectiveBudget * 1.10 (climb compensation + densify headroom) -- nawet jesli rk > budget
        return new Result(surplus > threshold ? Verdict.SURPLUS : Verdict.OK, budget, Math.max(0, surplus));
    }

    /**
     * Pre-screen verdict: czy SAM baseline (start→via→meta bez gmin) mieści się w budżecie.
     * BUDGET_IMPOSSIBLE = user musi poprawić parametry (mniej dni / krótszy/inny via / inna meta).
     * Wołane PRZED reconcile loop — jeśli już baseline przekracza budżet, nie ma sensu wybierać obszarów.
     */
    public static Result evaluateBaseline(double baselineKm, Integer days, Integer kmPerDay) {
        if (days == null || kmPerDay == null || days <= 0 || kmPerDay <= 0) {
            return new Result(Verdict.OK, 0, 0);
        }
        int budget = days * kmPerDay;
        int bk = (int) Math.round(baselineKm);
        if (bk > budget * 1.05) {
            return new Result(Verdict.BUDGET_IMPOSSIBLE, budget, 0);
        }
        return new Result(Verdict.OK, budget, Math.max(0, budget - bk));
    }
}
