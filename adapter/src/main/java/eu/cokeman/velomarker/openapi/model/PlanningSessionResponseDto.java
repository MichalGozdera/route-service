package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import eu.cokeman.velomarker.openapi.model.PlanningIntentDto;
import eu.cokeman.velomarker.openapi.model.PlanningSessionDayDto;
import eu.cokeman.velomarker.openapi.model.PlanningSummaryDto;
import eu.cokeman.velomarker.openapi.model.RoutePreferencesDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * PlanningSessionResponseDto
 */

@JsonTypeName("PlanningSessionResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class PlanningSessionResponseDto {

  private UUID id;

  private UUID userId;

  private @Nullable PlanningIntentDto intent;

  private RoutePreferencesDto preferences;

  @Valid
  private List<@Valid PlanningSessionDayDto> days = new ArrayList<>();

  private @Nullable UUID lastTaskId = null;

  private @Nullable PlanningSummaryDto summary;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant createdAt;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant updatedAt;

  public PlanningSessionResponseDto() {
    super();
  }

  public PlanningSessionResponseDto id(UUID id) {
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

  public PlanningSessionResponseDto userId(UUID userId) {
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

  public PlanningSessionResponseDto intent(@Nullable PlanningIntentDto intent) {
    this.intent = intent;
    return this;
  }

  /**
   * Get intent
   * @return intent
   */
  @Valid 
  @Schema(name = "intent", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("intent")
  public @Nullable PlanningIntentDto getIntent() {
    return intent;
  }

  @JsonProperty("intent")
  public void setIntent(@Nullable PlanningIntentDto intent) {
    this.intent = intent;
  }

  public PlanningSessionResponseDto preferences(RoutePreferencesDto preferences) {
    this.preferences = preferences;
    return this;
  }

  /**
   * Get preferences
   * @return preferences
   */
  @NotNull @Valid 
  @Schema(name = "preferences", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("preferences")
  public RoutePreferencesDto getPreferences() {
    return preferences;
  }

  @JsonProperty("preferences")
  public void setPreferences(RoutePreferencesDto preferences) {
    this.preferences = preferences;
  }

  public PlanningSessionResponseDto days(List<@Valid PlanningSessionDayDto> days) {
    this.days = days;
    return this;
  }

  public PlanningSessionResponseDto addDaysItem(PlanningSessionDayDto daysItem) {
    if (this.days == null) {
      this.days = new ArrayList<>();
    }
    this.days.add(daysItem);
    return this;
  }

  /**
   * Get days
   * @return days
   */
  @NotNull @Valid 
  @Schema(name = "days", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("days")
  public List<@Valid PlanningSessionDayDto> getDays() {
    return days;
  }

  @JsonProperty("days")
  public void setDays(List<@Valid PlanningSessionDayDto> days) {
    this.days = days;
  }

  public PlanningSessionResponseDto lastTaskId(@Nullable UUID lastTaskId) {
    this.lastTaskId = lastTaskId;
    return this;
  }

  /**
   * Get lastTaskId
   * @return lastTaskId
   */
  @Valid 
  @Schema(name = "lastTaskId", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("lastTaskId")
  public @Nullable UUID getLastTaskId() {
    return lastTaskId;
  }

  @JsonProperty("lastTaskId")
  public void setLastTaskId(@Nullable UUID lastTaskId) {
    this.lastTaskId = lastTaskId;
  }

  public PlanningSessionResponseDto summary(@Nullable PlanningSummaryDto summary) {
    this.summary = summary;
    return this;
  }

  /**
   * Get summary
   * @return summary
   */
  @Valid 
  @Schema(name = "summary", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("summary")
  public @Nullable PlanningSummaryDto getSummary() {
    return summary;
  }

  @JsonProperty("summary")
  public void setSummary(@Nullable PlanningSummaryDto summary) {
    this.summary = summary;
  }

  public PlanningSessionResponseDto createdAt(Instant createdAt) {
    this.createdAt = createdAt;
    return this;
  }

  /**
   * Get createdAt
   * @return createdAt
   */
  @NotNull @Valid 
  @Schema(name = "createdAt", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("createdAt")
  public Instant getCreatedAt() {
    return createdAt;
  }

  @JsonProperty("createdAt")
  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public PlanningSessionResponseDto updatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
    return this;
  }

  /**
   * Get updatedAt
   * @return updatedAt
   */
  @NotNull @Valid 
  @Schema(name = "updatedAt", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("updatedAt")
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @JsonProperty("updatedAt")
  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlanningSessionResponseDto planningSessionResponse = (PlanningSessionResponseDto) o;
    return Objects.equals(this.id, planningSessionResponse.id) &&
        Objects.equals(this.userId, planningSessionResponse.userId) &&
        Objects.equals(this.intent, planningSessionResponse.intent) &&
        Objects.equals(this.preferences, planningSessionResponse.preferences) &&
        Objects.equals(this.days, planningSessionResponse.days) &&
        Objects.equals(this.lastTaskId, planningSessionResponse.lastTaskId) &&
        Objects.equals(this.summary, planningSessionResponse.summary) &&
        Objects.equals(this.createdAt, planningSessionResponse.createdAt) &&
        Objects.equals(this.updatedAt, planningSessionResponse.updatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, userId, intent, preferences, days, lastTaskId, summary, createdAt, updatedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PlanningSessionResponseDto {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
    sb.append("    intent: ").append(toIndentedString(intent)).append("\n");
    sb.append("    preferences: ").append(toIndentedString(preferences)).append("\n");
    sb.append("    days: ").append(toIndentedString(days)).append("\n");
    sb.append("    lastTaskId: ").append(toIndentedString(lastTaskId)).append("\n");
    sb.append("    summary: ").append(toIndentedString(summary)).append("\n");
    sb.append("    createdAt: ").append(toIndentedString(createdAt)).append("\n");
    sb.append("    updatedAt: ").append(toIndentedString(updatedAt)).append("\n");
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

