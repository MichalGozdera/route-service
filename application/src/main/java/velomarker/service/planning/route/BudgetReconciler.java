package velomarker.service.planning.route;

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

/** Pre-screen: czy SAM baseline (start→via→meta bez gmin) mieści się w budżecie (dni × km/dzień). */
public final class BudgetReconciler {

    private BudgetReconciler() {}

    /** BUDGET_IMPOSSIBLE gdy baseline > budżet×1.05 — user musi poprawić parametry (mniej dni / krótszy via / inna meta). */
    public static Result evaluateBaseline(double baselineKm, Integer days, Integer kmPerDay) {
        if (days == null || kmPerDay == null || days <= 0 || kmPerDay <= 0) {
            return new Result(Verdict.OK);
        }
        int budget = days * kmPerDay;
        return Math.round(baselineKm) > budget * 1.05
                ? new Result(Verdict.BUDGET_IMPOSSIBLE)
                : new Result(Verdict.OK);
    }
}
