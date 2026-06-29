package velomarker.service.planning;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetReconcilerTest {

    @Test
    void evaluateBaseline_baselineFitsInBudget_returnsOK() {
        var r = BudgetReconciler.evaluateBaseline(850, 10, 200);
        assertThat(r.verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    void evaluateBaseline_baselineExceedsBudget_returnsBUDGET_IMPOSSIBLE() {
        var r = BudgetReconciler.evaluateBaseline(1500, 2, 100);
        assertThat(r.verdict()).isEqualTo(Verdict.BUDGET_IMPOSSIBLE);
    }

    @Test
    void evaluateBaseline_baselineExactlyAt105Percent_isOK_notImpossible() {
        var r = BudgetReconciler.evaluateBaseline(1050, 10, 100);
        assertThat(r.verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    void evaluateBaseline_nullDays_returnsOK() {
        var r = BudgetReconciler.evaluateBaseline(500, null, 100);
        assertThat(r.verdict()).isEqualTo(Verdict.OK);
    }
}
