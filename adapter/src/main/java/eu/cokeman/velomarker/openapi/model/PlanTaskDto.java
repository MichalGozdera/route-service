package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import eu.cokeman.velomarker.openapi.model.PlanTaskStatusDto;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * PlanTaskDto
 */

@JsonTypeName("PlanTask")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class PlanTaskDto {

  private UUID id;

  private UUID sessionId;

  private UUID userId;

  private PlanTaskStatusDto status;

  private @Nullable String phase = null;

  private @Nullable Integer progressCurrent = null;

  private @Nullable Integer progressTotal = null;

  private @Nullable String error = null;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant startedAt;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable Instant completedAt = null;

  public PlanTaskDto() {
    super();
  }

  public PlanTaskDto id(UUID id) {
    this.id = id;
    return this;
  }

  /**
   * Get id
   * @return id
   */
  @NotNull @Valid 
  @Schema(name = "id", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("id")
  public UUID getId() {
    return id;
  }

  @JsonProperty("id")
  public void setId(UUID id) {
    this.id = id;
  }

  public PlanTaskDto sessionId(UUID sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  /**
   * Get sessionId
   * @return sessionId
   */
  @NotNull @Valid 
  @Schema(name = "sessionId", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("sessionId")
  public UUID getSessionId() {
    return sessionId;
  }

  @JsonProperty("sessionId")
  public void setSessionId(UUID sessionId) {
    this.sessionId = sessionId;
  }

  public PlanTaskDto userId(UUID userId) {
    this.userId = userId;
    return this;
  }

  /**
   * Get userId
   * @return userId
   */
  @NotNull @Valid 
  @Schema(name = "userId", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("userId")
  public UUID getUserId() {
    return userId;
  }

  @JsonProperty("userId")
  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public PlanTaskDto status(PlanTaskStatusDto status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * @return status
   */
  @NotNull @Valid 
  @Schema(name = "status", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("status")
  public PlanTaskStatusDto getStatus() {
    return status;
  }

  @JsonProperty("status")
  public void setStatus(PlanTaskStatusDto status) {
    this.status = status;
  }

  public PlanTaskDto phase(@Nullable String phase) {
    this.phase = phase;
    return this;
  }

  /**
   * Current phase name (validating / fetching-areas / ordering-areas / routing-brouter / sampling-elevation / splitting-days / computing-day-N / saving / done)
   * @return phase
   */
  
  @Schema(name = "phase", description = "Current phase name (validating / fetching-areas / ordering-areas / routing-brouter / sampling-elevation / splitting-days / computing-day-N / saving / done)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("phase")
  public @Nullable String getPhase() {
    return phase;
  }

  @JsonProperty("phase")
  public void setPhase(@Nullable String phase) {
    this.phase = phase;
  }

  public PlanTaskDto progressCurrent(@Nullable Integer progressCurrent) {
    this.progressCurrent = progressCurrent;
    return this;
  }

  /**
   * Get progressCurrent
   * @return progressCurrent
   */
  
  @Schema(name = "progressCurrent", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("progressCurrent")
  public @Nullable Integer getProgressCurrent() {
    return progressCurrent;
  }

  @JsonProperty("progressCurrent")
  public void setProgressCurrent(@Nullable Integer progressCurrent) {
    this.progressCurrent = progressCurrent;
  }

  public PlanTaskDto progressTotal(@Nullable Integer progressTotal) {
    this.progressTotal = progressTotal;
    return this;
  }

  /**
   * Get progressTotal
   * @return progressTotal
   */
  
  @Schema(name = "progressTotal", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("progressTotal")
  public @Nullable Integer getProgressTotal() {
    return progressTotal;
  }

  @JsonProperty("progressTotal")
  public void setProgressTotal(@Nullable Integer progressTotal) {
    this.progressTotal = progressTotal;
  }

  public PlanTaskDto error(@Nullable String error) {
    this.error = error;
    return this;
  }

  /**
   * Get error
   * @return error
   */
  
  @Schema(name = "error", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("error")
  public @Nullable String getError() {
    return error;
  }

  @JsonProperty("error")
  public void setError(@Nullable String error) {
    this.error = error;
  }

  public PlanTaskDto startedAt(Instant startedAt) {
    this.startedAt = startedAt;
    return this;
  }

  /**
   * Get startedAt
   * @return startedAt
   */
  @NotNull @Valid 
  @Schema(name = "startedAt", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("startedAt")
  public Instant getStartedAt() {
    return startedAt;
  }

  @JsonProperty("startedAt")
  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public PlanTaskDto completedAt(@Nullable Instant completedAt) {
    this.completedAt = completedAt;
    return this;
  }

  /**
   * Get completedAt
   * @return completedAt
   */
  @Valid 
  @Schema(name = "completedAt", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("completedAt")
  public @Nullable Instant getCompletedAt() {
    return completedAt;
  }

  @JsonProperty("completedAt")
  public void setCompletedAt(@Nullable Instant completedAt) {
    this.completedAt = completedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlanTaskDto planTask = (PlanTaskDto) o;
    return Objects.equals(this.id, planTask.id) &&
        Objects.equals(this.sessionId, planTask.sessionId) &&
        Objects.equals(this.userId, planTask.userId) &&
        Objects.equals(this.status, planTask.status) &&
        Objects.equals(this.phase, planTask.phase) &&
        Objects.equals(this.progressCurrent, planTask.progressCurrent) &&
        Objects.equals(this.progressTotal, planTask.progressTotal) &&
        Objects.equals(this.error, planTask.error) &&
        Objects.equals(this.startedAt, planTask.startedAt) &&
        Objects.equals(this.completedAt, planTask.completedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, sessionId, userId, status, phase, progressCurrent, progressTotal, error, startedAt, completedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PlanTaskDto {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    sessionId: ").append(toIndentedString(sessionId)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
    sb.append("    phase: ").append(toIndentedString(phase)).append("\n");
    sb.append("    progressCurrent: ").append(toIndentedString(progressCurrent)).append("\n");
    sb.append("    progressTotal: ").append(toIndentedString(progressTotal)).append("\n");
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
    sb.append("    startedAt: ").append(toIndentedString(startedAt)).append("\n");
    sb.append("    completedAt: ").append(toIndentedString(completedAt)).append("\n");
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

