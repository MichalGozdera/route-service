package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import eu.cokeman.velomarker.openapi.model.PlanningIntentDto;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * SetPlanningIntentRequestDto
 */

@JsonTypeName("SetPlanningIntentRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class SetPlanningIntentRequestDto {

  private PlanningIntentDto intent;

  public SetPlanningIntentRequestDto() {
    super();
  }

  public SetPlanningIntentRequestDto intent(PlanningIntentDto intent) {
    this.intent = intent;
    return this;
  }

  /**
   * Get intent
   * @return intent
   */
  @NotNull @Valid 
  @Schema(name = "intent", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("intent")
  public PlanningIntentDto getIntent() {
    return intent;
  }

  @JsonProperty("intent")
  public void setIntent(PlanningIntentDto intent) {
    this.intent = intent;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SetPlanningIntentRequestDto setPlanningIntentRequest = (SetPlanningIntentRequestDto) o;
    return Objects.equals(this.intent, setPlanningIntentRequest.intent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(intent);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class SetPlanningIntentRequestDto {\n");
    sb.append("    intent: ").append(toIndentedString(intent)).append("\n");
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

