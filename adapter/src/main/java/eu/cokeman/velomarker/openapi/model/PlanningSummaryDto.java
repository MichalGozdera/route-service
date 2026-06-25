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
 * Podsumowanie policzonej wyprawy. Null gdy sesja jeszcze nie była policzona.
 */

@Schema(name = "PlanningSummary", description = "Podsumowanie policzonej wyprawy. Null gdy sesja jeszcze nie była policzona.")
@JsonTypeName("PlanningSummary")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class PlanningSummaryDto {

  private Double totalDistanceKm;

  private Integer totalElevationGain;

  private Integer budgetKm;

  /**
   * BUDGET_IMPOSSIBLE = sam baseline (start→via→meta) przekracza budżet — user musi poprawić parametry.
   */
  public enum VerdictEnum {
    OK("OK"),
    
    SURPLUS("SURPLUS"),
    
    DEFICIT("DEFICIT"),
    
    BUDGET_IMPOSSIBLE("BUDGET_IMPOSSIBLE");

    private final String value;

    VerdictEnum(String value) {
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
    public static VerdictEnum fromValue(String value) {
      for (VerdictEnum b : VerdictEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private VerdictEnum verdict;

  private Integer surplusKm;

  private Integer poolSize;

  private Integer initialPoolSize;

  private @Nullable Double baselineKm = null;

  private @Nullable Double roadAreas = null;

  private @Nullable Boolean climbWarning;

  public PlanningSummaryDto() {
    super();
  }

  public PlanningSummaryDto totalDistanceKm(Double totalDistanceKm) {
    this.totalDistanceKm = totalDistanceKm;
    return this;
  }

  /**
   * Get totalDistanceKm
   * @return totalDistanceKm
   */
  @NotNull 
  @Schema(name = "totalDistanceKm", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("totalDistanceKm")
  public Double getTotalDistanceKm() {
    return totalDistanceKm;
  }

  @JsonProperty("totalDistanceKm")
  public void setTotalDistanceKm(Double totalDistanceKm) {
    this.totalDistanceKm = totalDistanceKm;
  }

  public PlanningSummaryDto totalElevationGain(Integer totalElevationGain) {
    this.totalElevationGain = totalElevationGain;
    return this;
  }

  /**
   * Get totalElevationGain
   * @return totalElevationGain
   */
  @NotNull 
  @Schema(name = "totalElevationGain", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("totalElevationGain")
  public Integer getTotalElevationGain() {
    return totalElevationGain;
  }

  @JsonProperty("totalElevationGain")
  public void setTotalElevationGain(Integer totalElevationGain) {
    this.totalElevationGain = totalElevationGain;
  }

  public PlanningSummaryDto budgetKm(Integer budgetKm) {
    this.budgetKm = budgetKm;
    return this;
  }

  /**
   * days × kmPerDay
   * @return budgetKm
   */
  @NotNull 
  @Schema(name = "budgetKm", description = "days × kmPerDay", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("budgetKm")
  public Integer getBudgetKm() {
    return budgetKm;
  }

  @JsonProperty("budgetKm")
  public void setBudgetKm(Integer budgetKm) {
    this.budgetKm = budgetKm;
  }

  public PlanningSummaryDto verdict(VerdictEnum verdict) {
    this.verdict = verdict;
    return this;
  }

  /**
   * BUDGET_IMPOSSIBLE = sam baseline (start→via→meta) przekracza budżet — user musi poprawić parametry.
   * @return verdict
   */
  @NotNull 
  @Schema(name = "verdict", description = "BUDGET_IMPOSSIBLE = sam baseline (start→via→meta) przekracza budżet — user musi poprawić parametry.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("verdict")
  public VerdictEnum getVerdict() {
    return verdict;
  }

  @JsonProperty("verdict")
  public void setVerdict(VerdictEnum verdict) {
    this.verdict = verdict;
  }

  public PlanningSummaryDto surplusKm(Integer surplusKm) {
    this.surplusKm = surplusKm;
    return this;
  }

  /**
   * Get surplusKm
   * @return surplusKm
   */
  @NotNull 
  @Schema(name = "surplusKm", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("surplusKm")
  public Integer getSurplusKm() {
    return surplusKm;
  }

  @JsonProperty("surplusKm")
  public void setSurplusKm(Integer surplusKm) {
    this.surplusKm = surplusKm;
  }

  public PlanningSummaryDto poolSize(Integer poolSize) {
    this.poolSize = poolSize;
    return this;
  }

  /**
   * Wybrana pula obszarów (finalna)
   * @return poolSize
   */
  @NotNull 
  @Schema(name = "poolSize", description = "Wybrana pula obszarów (finalna)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("poolSize")
  public Integer getPoolSize() {
    return poolSize;
  }

  @JsonProperty("poolSize")
  public void setPoolSize(Integer poolSize) {
    this.poolSize = poolSize;
  }

  public PlanningSummaryDto initialPoolSize(Integer initialPoolSize) {
    this.initialPoolSize = initialPoolSize;
    return this;
  }

  /**
   * Pula kandydatów po scoringu (przed wyborem)
   * @return initialPoolSize
   */
  @NotNull 
  @Schema(name = "initialPoolSize", description = "Pula kandydatów po scoringu (przed wyborem)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("initialPoolSize")
  public Integer getInitialPoolSize() {
    return initialPoolSize;
  }

  @JsonProperty("initialPoolSize")
  public void setInitialPoolSize(Integer initialPoolSize) {
    this.initialPoolSize = initialPoolSize;
  }

  public PlanningSummaryDto baselineKm(@Nullable Double baselineKm) {
    this.baselineKm = baselineKm;
    return this;
  }

  /**
   * Baseline BRouter (start→via→meta bez gmin). Dolna granica trasy.
   * @return baselineKm
   */
  
  @Schema(name = "baselineKm", description = "Baseline BRouter (start→via→meta bez gmin). Dolna granica trasy.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("baselineKm")
  public @Nullable Double getBaselineKm() {
    return baselineKm;
  }

  @JsonProperty("baselineKm")
  public void setBaselineKm(@Nullable Double baselineKm) {
    this.baselineKm = baselineKm;
  }

  public PlanningSummaryDto roadAreas(@Nullable Double roadAreas) {
    this.roadAreas = roadAreas;
    return this;
  }

  /**
   * Współczynnik road/straight dla gęstej puli obszarów (density probe). Diagnostyka.
   * @return roadAreas
   */
  
  @Schema(name = "roadAreas", description = "Współczynnik road/straight dla gęstej puli obszarów (density probe). Diagnostyka.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("roadAreas")
  public @Nullable Double getRoadAreas() {
    return roadAreas;
  }

  @JsonProperty("roadAreas")
  public void setRoadAreas(@Nullable Double roadAreas) {
    this.roadAreas = roadAreas;
  }

  public PlanningSummaryDto climbWarning(@Nullable Boolean climbWarning) {
    this.climbWarning = climbWarning;
    return this;
  }

  /**
   * true gdy totalElevationGain > refClimbTotal × 1.10 — user-facing warning, nie blokuje verdict.
   * @return climbWarning
   */
  
  @Schema(name = "climbWarning", description = "true gdy totalElevationGain > refClimbTotal × 1.10 — user-facing warning, nie blokuje verdict.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("climbWarning")
  public @Nullable Boolean getClimbWarning() {
    return climbWarning;
  }

  @JsonProperty("climbWarning")
  public void setClimbWarning(@Nullable Boolean climbWarning) {
    this.climbWarning = climbWarning;
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
    return Objects.equals(this.totalDistanceKm, planningSummary.totalDistanceKm) &&
        Objects.equals(this.totalElevationGain, planningSummary.totalElevationGain) &&
        Objects.equals(this.budgetKm, planningSummary.budgetKm) &&
        Objects.equals(this.verdict, planningSummary.verdict) &&
        Objects.equals(this.surplusKm, planningSummary.surplusKm) &&
        Objects.equals(this.poolSize, planningSummary.poolSize) &&
        Objects.equals(this.initialPoolSize, planningSummary.initialPoolSize) &&
        Objects.equals(this.baselineKm, planningSummary.baselineKm) &&
        Objects.equals(this.roadAreas, planningSummary.roadAreas) &&
        Objects.equals(this.climbWarning, planningSummary.climbWarning);
  }

  @Override
  public int hashCode() {
    return Objects.hash(totalDistanceKm, totalElevationGain, budgetKm, verdict, surplusKm, poolSize, initialPoolSize, baselineKm, roadAreas, climbWarning);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PlanningSummaryDto {\n");
    sb.append("    totalDistanceKm: ").append(toIndentedString(totalDistanceKm)).append("\n");
    sb.append("    totalElevationGain: ").append(toIndentedString(totalElevationGain)).append("\n");
    sb.append("    budgetKm: ").append(toIndentedString(budgetKm)).append("\n");
    sb.append("    verdict: ").append(toIndentedString(verdict)).append("\n");
    sb.append("    surplusKm: ").append(toIndentedString(surplusKm)).append("\n");
    sb.append("    poolSize: ").append(toIndentedString(poolSize)).append("\n");
    sb.append("    initialPoolSize: ").append(toIndentedString(initialPoolSize)).append("\n");
    sb.append("    baselineKm: ").append(toIndentedString(baselineKm)).append("\n");
    sb.append("    roadAreas: ").append(toIndentedString(roadAreas)).append("\n");
    sb.append("    climbWarning: ").append(toIndentedString(climbWarning)).append("\n");
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

