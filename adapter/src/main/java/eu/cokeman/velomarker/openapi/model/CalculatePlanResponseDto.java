package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.UUID;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * CalculatePlanResponseDto
 */

@JsonTypeName("CalculatePlanResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class CalculatePlanResponseDto {

  private UUID taskId;

  public CalculatePlanResponseDto() {
    super();
  }

  public CalculatePlanResponseDto taskId(UUID taskId) {
    this.taskId = taskId;
    return this;
  }

  /**
   * UUID of submitted PLAN_ROUTE task (poll via GET /planning/task/{taskId})
   * @return taskId
   */
  @NotNull @Valid 
  @Schema(name = "taskId", description = "UUID of submitted PLAN_ROUTE task (poll via GET /planning/task/{taskId})", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("taskId")
  public UUID getTaskId() {
    return taskId;
  }

  @JsonProperty("taskId")
  public void setTaskId(UUID taskId) {
    this.taskId = taskId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CalculatePlanResponseDto calculatePlanResponse = (CalculatePlanResponseDto) o;
    return Objects.equals(this.taskId, calculatePlanResponse.taskId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(taskId);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CalculatePlanResponseDto {\n");
    sb.append("    taskId: ").append(toIndentedString(taskId)).append("\n");
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

