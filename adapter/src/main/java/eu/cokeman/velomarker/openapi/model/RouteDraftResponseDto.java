package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.LineStringGeoJsonDto;
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
 * RouteDraftResponseDto
 */

@JsonTypeName("RouteDraftResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RouteDraftResponseDto {

  private UUID id;

  private String name;

  private LineStringGeoJsonDto geometry;

  private String profile;

  private @Nullable Double distanceKm = null;

  private @Nullable Integer elevationGain = null;

  private @Nullable Integer elevationLoss = null;

  private @Nullable UUID groupId = null;

  private @Nullable String groupName = null;

  private @Nullable Integer dayNumber = null;

  private @Nullable String waypoints = null;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant createdAt;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant updatedAt;

  public RouteDraftResponseDto() {
    super();
  }

  public RouteDraftResponseDto id(UUID id) {
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

  public RouteDraftResponseDto name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Get name
   * @return name
   */
  @NotNull 
  @Schema(name = "name", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public RouteDraftResponseDto geometry(LineStringGeoJsonDto geometry) {
    this.geometry = geometry;
    return this;
  }

  /**
   * Get geometry
   * @return geometry
   */
  @NotNull @Valid 
  @Schema(name = "geometry", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("geometry")
  public LineStringGeoJsonDto getGeometry() {
    return geometry;
  }

  @JsonProperty("geometry")
  public void setGeometry(LineStringGeoJsonDto geometry) {
    this.geometry = geometry;
  }

  public RouteDraftResponseDto profile(String profile) {
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

  public RouteDraftResponseDto distanceKm(@Nullable Double distanceKm) {
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

  public RouteDraftResponseDto elevationGain(@Nullable Integer elevationGain) {
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

  public RouteDraftResponseDto elevationLoss(@Nullable Integer elevationLoss) {
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

  public RouteDraftResponseDto groupId(@Nullable UUID groupId) {
    this.groupId = groupId;
    return this;
  }

  /**
   * Get groupId
   * @return groupId
   */
  @Valid 
  @Schema(name = "groupId", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("groupId")
  public @Nullable UUID getGroupId() {
    return groupId;
  }

  @JsonProperty("groupId")
  public void setGroupId(@Nullable UUID groupId) {
    this.groupId = groupId;
  }

  public RouteDraftResponseDto groupName(@Nullable String groupName) {
    this.groupName = groupName;
    return this;
  }

  /**
   * Get groupName
   * @return groupName
   */
  
  @Schema(name = "groupName", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("groupName")
  public @Nullable String getGroupName() {
    return groupName;
  }

  @JsonProperty("groupName")
  public void setGroupName(@Nullable String groupName) {
    this.groupName = groupName;
  }

  public RouteDraftResponseDto dayNumber(@Nullable Integer dayNumber) {
    this.dayNumber = dayNumber;
    return this;
  }

  /**
   * Get dayNumber
   * @return dayNumber
   */
  
  @Schema(name = "dayNumber", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("dayNumber")
  public @Nullable Integer getDayNumber() {
    return dayNumber;
  }

  @JsonProperty("dayNumber")
  public void setDayNumber(@Nullable Integer dayNumber) {
    this.dayNumber = dayNumber;
  }

  public RouteDraftResponseDto waypoints(@Nullable String waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  /**
   * Get waypoints
   * @return waypoints
   */
  
  @Schema(name = "waypoints", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("waypoints")
  public @Nullable String getWaypoints() {
    return waypoints;
  }

  @JsonProperty("waypoints")
  public void setWaypoints(@Nullable String waypoints) {
    this.waypoints = waypoints;
  }

  public RouteDraftResponseDto createdAt(Instant createdAt) {
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

  public RouteDraftResponseDto updatedAt(Instant updatedAt) {
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
    RouteDraftResponseDto routeDraftResponse = (RouteDraftResponseDto) o;
    return Objects.equals(this.id, routeDraftResponse.id) &&
        Objects.equals(this.name, routeDraftResponse.name) &&
        Objects.equals(this.geometry, routeDraftResponse.geometry) &&
        Objects.equals(this.profile, routeDraftResponse.profile) &&
        Objects.equals(this.distanceKm, routeDraftResponse.distanceKm) &&
        Objects.equals(this.elevationGain, routeDraftResponse.elevationGain) &&
        Objects.equals(this.elevationLoss, routeDraftResponse.elevationLoss) &&
        Objects.equals(this.groupId, routeDraftResponse.groupId) &&
        Objects.equals(this.groupName, routeDraftResponse.groupName) &&
        Objects.equals(this.dayNumber, routeDraftResponse.dayNumber) &&
        Objects.equals(this.waypoints, routeDraftResponse.waypoints) &&
        Objects.equals(this.createdAt, routeDraftResponse.createdAt) &&
        Objects.equals(this.updatedAt, routeDraftResponse.updatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name, geometry, profile, distanceKm, elevationGain, elevationLoss, groupId, groupName, dayNumber, waypoints, createdAt, updatedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RouteDraftResponseDto {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    geometry: ").append(toIndentedString(geometry)).append("\n");
    sb.append("    profile: ").append(toIndentedString(profile)).append("\n");
    sb.append("    distanceKm: ").append(toIndentedString(distanceKm)).append("\n");
    sb.append("    elevationGain: ").append(toIndentedString(elevationGain)).append("\n");
    sb.append("    elevationLoss: ").append(toIndentedString(elevationLoss)).append("\n");
    sb.append("    groupId: ").append(toIndentedString(groupId)).append("\n");
    sb.append("    groupName: ").append(toIndentedString(groupName)).append("\n");
    sb.append("    dayNumber: ").append(toIndentedString(dayNumber)).append("\n");
    sb.append("    waypoints: ").append(toIndentedString(waypoints)).append("\n");
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

