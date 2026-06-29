package velomarker.entity.planning;

/** Dopasowanie policzonej wyprawy do budżetu effortu (km + 0.1·wznios): UNDER &lt;95%, OK, OVER &gt;105%. */
public record PlanningSummary(BudgetFit budgetFit) {

    public enum BudgetFit { UNDER, OK, OVER }
}
