package velomarker.service.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetReconcilerTest {

    @Test
    void evaluate_routeFitsExactly_returnsOK() {
        var r = BudgetReconciler.evaluate(1000, 10, 100); // budget 1000
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
        assertThat(r.budgetKm()).isEqualTo(1000);
        assertThat(r.surplusKm()).isZero();
    }

    @Test
    void evaluate_routeWithinTolerance_returnsOK() {
        // 1040 km, budżet 1000 → 4% over, ale < 5% tolerance → OK
        var r = BudgetReconciler.evaluate(1040, 10, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
    }

    @Test
    void evaluate_routeAboveTolerance_returnsDEFICIT() {
        var r = BudgetReconciler.evaluate(1120, 10, 100); // 12% over, > 10% (densify ceiling) → DEFICIT
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.DEFICIT);
        assertThat(r.surplusKm()).isZero();
    }

    @Test
    void evaluate_routeWithinDensifyHeadroom_returnsOK() {
        // 1080 km / budżet 1000 → 8% over, < 10% (densify dopina dziury ponad budżet) → OK, nie DEFICIT
        var r = BudgetReconciler.evaluate(1080, 10, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
    }

    @Test
    void evaluate_routeMuchShorter_returnsSURPLUS() {
        // 700 km / budget 1000 → surplus 300 = 30% > max(20, 10%) → SURPLUS
        var r = BudgetReconciler.evaluate(700, 10, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.SURPLUS);
        assertThat(r.surplusKm()).isEqualTo(300);
    }

    @Test
    void evaluate_smallSurplusWithinThreshold_returnsOK() {
        // 985 km / 1000 → surplus 15 < max(20, 100) → OK
        var r = BudgetReconciler.evaluate(985, 10, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
    }

    @Test
    void evaluate_nullDays_returnsOK_zeroBudget() {
        var r = BudgetReconciler.evaluate(500, null, 100);
        assertThat(r.verdict()).isEqualTo(BudgetReconciler.Verdict.OK);
        assertThat(r.budgetKm()).isZero();
    }

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
