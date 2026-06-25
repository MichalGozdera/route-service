package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.RouteStatsDto;
import eu.cokeman.velomarker.openapi.model.WaypointDto;
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
 * PlanningSessionDayDto
 */

@JsonTypeName("PlanningSessionDay")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class PlanningSessionDayDto {

  private UUID id;

  private UUID sessionId;

  private Integer dayNumber;

  private String geometryEncoded;

  @Valid
  private List<@Valid WaypointDto> waypoints = new ArrayList<>();

  private @Nullable Double distanceKm = null;

  private @Nullable Integer elevationGain = null;

  private @Nullable Integer elevationLoss = null;

  private @Nullable RouteStatsDto stats;

  private String profile;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant editedAt;

  public PlanningSessionDayDto() {
    super();
  }

  public PlanningSessionDayDto id(UUID id) {
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

  public PlanningSessionDayDto sessionId(UUID sessionId) {
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

  public PlanningSessionDayDto dayNumber(Integer dayNumber) {
    this.dayNumber = dayNumber;
    return this;
  }

  /**
   * Get dayNumber
   * @return dayNumber
   */
  @NotNull 
  @Schema(name = "dayNumber", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("dayNumber")
  public Integer getDayNumber() {
    return dayNumber;
  }

  @JsonProperty("dayNumber")
  public void setDayNumber(Integer dayNumber) {
    this.dayNumber = dayNumber;
  }

  public PlanningSessionDayDto geometryEncoded(String geometryEncoded) {
    this.geometryEncoded = geometryEncoded;
    return this;
  }

  /**
   * Polyline3DCodec encoded (lng,lat,z)
   * @return geometryEncoded
   */
  @NotNull 
  @Schema(name = "geometryEncoded", description = "Polyline3DCodec encoded (lng,lat,z)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("geometryEncoded")
  public String getGeometryEncoded() {
    return geometryEncoded;
  }

  @JsonProperty("geometryEncoded")
  public void setGeometryEncoded(String geometryEncoded) {
    this.geometryEncoded = geometryEncoded;
  }

  public PlanningSessionDayDto waypoints(List<@Valid WaypointDto> waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  public PlanningSessionDayDto addWaypointsItem(WaypointDto waypointsItem) {
    if (this.waypoints == null) {
      this.waypoints = new ArrayList<>();
    }
    this.waypoints.add(waypointsItem);
    return this;
  }

  /**
   * Get waypoints
   * @return waypoints
   */
  @NotNull @Valid 
  @Schema(name = "waypoints", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("waypoints")
  public List<@Valid WaypointDto> getWaypoints() {
    return waypoints;
  }

  @JsonProperty("waypoints")
  public void setWaypoints(List<@Valid WaypointDto> waypoints) {
    this.waypoints = waypoints;
  }

  public PlanningSessionDayDto distanceKm(@Nullable Double distanceKm) {
    this.distanceKm = distanceKm;
    return this;
  }

  /**
   * Get distanceKm
   * @return distanceKm
   */
  
  @Schema(name = "distanceKm", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("distanceKm")
  public @Nullable Double getDistanceKm() {
    return distanceKm;
  }

  @JsonProperty("distanceKm")
  public void setDistanceKm(@Nullable Double distanceKm) {
    this.distanceKm = distanceKm;
  }

  public PlanningSessionDayDto elevationGain(@Nullable Integer elevationGain) {
    this.elevationGain = elevationGain;
    return this;
  }

  /**
   * Get elevationGain
   * @return elevationGain
   */
  
  @Schema(name = "elevationGain", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("elevationGain")
  public @Nullable Integer getElevationGain() {
    return elevationGain;
  }

  @JsonProperty("elevationGain")
  public void setElevationGain(@Nullable Integer elevationGain) {
    this.elevationGain = elevationGain;
  }

  public PlanningSessionDayDto elevationLoss(@Nullable Integer elevationLoss) {
    this.elevationLoss = elevationLoss;
    return this;
  }

  /**
   * Get elevationLoss
   * @return elevationLoss
   */
  
  @Schema(name = "elevationLoss", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("elevationLoss")
  public @Nullable Integer getElevationLoss() {
    return elevationLoss;
  }

  @JsonProperty("elevationLoss")
  public void setElevationLoss(@Nullable Integer elevationLoss) {
    this.elevationLoss = elevationLoss;
  }

  public PlanningSessionDayDto stats(@Nullable RouteStatsDto stats) {
    this.stats = stats;
    return this;
  }

  /**
   * Get stats
   * @return stats
   */
  @Valid 
  @Schema(name = "stats", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("stats")
  public @Nullable RouteStatsDto getStats() {
    return stats;
  }

  @JsonProperty("stats")
  public void setStats(@Nullable RouteStatsDto stats) {
    this.stats = stats;
  }

  public PlanningSessionDayDto profile(String profile) {
    this.profile = profile;
    return this;
  }

  /**
   * Get profile
   * @return profile
   */
  @NotNull 
  @Schema(name = "profile", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("profile")
  public String getProfile() {
    return profile;
  }

  @JsonProperty("profile")
  public void setProfile(String profile) {
    this.profile = profile;
  }

  public PlanningSessionDayDto editedAt(Instant editedAt) {
    this.editedAt = editedAt;
    return this;
  }

  /**
   * Get editedAt
   * @return editedAt
   */
  @NotNull @Valid 
  @Schema(name = "editedAt", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("editedAt")
  public Instant getEditedAt() {
    return editedAt;
  }

  @JsonProperty("editedAt")
  public void setEditedAt(Instant editedAt) {
    this.editedAt = editedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlanningSessionDayDto planningSessionDay = (PlanningSessionDayDto) o;
    return Objects.equals(this.id, planningSessionDay.id) &&
        Objects.equals(this.sessionId, planningSessionDay.sessionId) &&
        Objects.equals(this.dayNumber, planningSessionDay.dayNumber) &&
        Objects.equals(this.geometryEncoded, planningSessionDay.geometryEncoded) &&
        Objects.equals(this.waypoints, planningSessionDay.waypoints) &&
        Objects.equals(this.distanceKm, planningSessionDay.distanceKm) &&
        Objects.equals(this.elevationGain, planningSessionDay.elevationGain) &&
        Objects.equals(this.elevationLoss, planningSessionDay.elevationLoss) &&
        Objects.equals(this.stats, planningSessionDay.stats) &&
        Objects.equals(this.profile, planningSessionDay.profile) &&
        Objects.equals(this.editedAt, planningSessionDay.editedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, sessionId, dayNumber, geometryEncoded, waypoints, distanceKm, elevationGain, elevationLoss, stats, profile, editedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PlanningSessionDayDto {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    sessionId: ").append(toIndentedString(sessionId)).append("\n");
    sb.append("    dayNumber: ").append(toIndentedString(dayNumber)).append("\n");
    sb.append("    geometryEncoded: ").append(toIndentedString(geometryEncoded)).append("\n");
    sb.append("    waypoints: ").append(toIndentedString(waypoints)).append("\n");
    sb.append("    distanceKm: ").append(toIndentedString(distanceKm)).append("\n");
    sb.append("    elevationGain: ").append(toIndentedString(elevationGain)).append("\n");
    sb.append("    elevationLoss: ").append(toIndentedString(elevationLoss)).append("\n");
    sb.append("    stats: ").append(toIndentedString(stats)).append("\n");
    sb.append("    profile: ").append(toIndentedString(profile)).append("\n");
    sb.append("    editedAt: ").append(toIndentedString(editedAt)).append("\n");
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

