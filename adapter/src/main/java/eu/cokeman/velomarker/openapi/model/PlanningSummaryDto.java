package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Dopasowanie policzonej wyprawy do budżetu effortu. Null gdy sesja jeszcze nie była policzona.
 */

@Schema(name = "PlanningSummary", description = "Dopasowanie policzonej wyprawy do budżetu effortu. Null gdy sesja jeszcze nie była policzona.")
@JsonTypeName("PlanningSummary")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class PlanningSummaryDto {

  /**
   * UNDER = trasa lekko za krótka (<95% budżetu), OVER = lekko za długa (>105%), OK = w paśmie.
   */
  public enum BudgetFitEnum {
    UNDER("UNDER"),
    
    OK("OK"),
    
    OVER("OVER");

    private final String value;

    BudgetFitEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @JsonCreator
    public static BudgetFitEnum fromValue(String value) {
      for (BudgetFitEnum b : BudgetFitEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private BudgetFitEnum budgetFit;

  public PlanningSummaryDto() {
    super();
  }

  public PlanningSummaryDto budgetFit(BudgetFitEnum budgetFit) {
    this.budgetFit = budgetFit;
    return this;
  }

  /**
   * UNDER = trasa lekko za krótka (<95% budżetu), OVER = lekko za długa (>105%), OK = w paśmie.
   * @return budgetFit
   */
  @NotNull 
  @Schema(name = "budgetFit", description = "UNDER = trasa lekko za krótka (<95% budżetu), OVER = lekko za długa (>105%), OK = w paśmie.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("budgetFit")
  public BudgetFitEnum getBudgetFit() {
    return budgetFit;
  }

  @JsonProperty("budgetFit")
  public void setBudgetFit(BudgetFitEnum budgetFit) {
    this.budgetFit = budgetFit;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlanningSummaryDto planningSummary = (PlanningSummaryDto) o;
    return Objects.equals(this.budgetFit, planningSummary.budgetFit);
  }

  @Override
  public int hashCode() {
    return Objects.hash(budgetFit);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PlanningSummaryDto {\n");
    sb.append("    budgetFit: ").append(toIndentedString(budgetFit)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

