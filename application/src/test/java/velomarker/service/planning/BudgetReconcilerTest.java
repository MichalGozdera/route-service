package velomarker.service.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetReconcilerTest {

    @Test
    void evaluateBaseline_baselineFitsInBudget_returnsOK() {
        // Baseline 850 km, budget 2000 → OK, surplus 1150
        var r = BudgetReconciler.evaluateBaseline(850, 10, 200);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
        assertThat(r.budgetKm()).isEqualTo(2000);
        assertThat(r.surplusKm()).isEqualTo(1150);
    }

    @Test
    void evaluateBaseline_baselineExceedsBudget_returnsBUDGET_IMPOSSIBLE() {
        // Warszawa→Rzym 1500 km baseline, 2 dni × 100 km = 200 budget → BUDGET_IMPOSSIBLE
        var r = BudgetReconciler.evaluateBaseline(1500, 2, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.BUDGET_IMPOSSIBLE);
        assertThat(r.surplusKm()).isZero();
    }

    @Test
    void evaluateBaseline_baselineExactlyAt105Percent_isOK_notImpossible() {
        // Baseline 1050 = budget × 1.05 = 1050 → tolerance, OK
        var r = BudgetReconciler.evaluateBaseline(1050, 10, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
    }

    @Test
    void evaluateBaseline_nullDays_returnsOK() {
        var r = BudgetReconciler.evaluateBaseline(500, null, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
        assertThat(r.budgetKm()).isZero();
    }
}
