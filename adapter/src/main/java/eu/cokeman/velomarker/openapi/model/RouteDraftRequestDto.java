package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.LineStringGeoJsonDto;
import java.util.UUID;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * RouteDraftRequestDto
 */

@JsonTypeName("RouteDraftRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RouteDraftRequestDto {

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

  public RouteDraftRequestDto() {
    super();
  }

  public RouteDraftRequestDto name(String name) {
    this.name = name;
    return this;
  }

  /**
   * User-facing draft name, unique per user
   * @return name
   */
  @NotNull @Size(min = 1, max = 255) 
  @Schema(name = "name", description = "User-facing draft name, unique per user", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public RouteDraftRequestDto geometry(LineStringGeoJsonDto geometry) {
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

  public RouteDraftRequestDto profile(String profile) {
    this.profile = profile;
    return this;
  }

  /**
   * BRouter profile used (snapshot at save time)
   * @return profile
   */
  @NotNull 
  @Schema(name = "profile", description = "BRouter profile used (snapshot at save time)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("profile")
  public String getProfile() {
    return profile;
  }

  @JsonProperty("profile")
  public void setProfile(String profile) {
    this.profile = profile;
  }

  public RouteDraftRequestDto distanceKm(@Nullable Double distanceKm) {
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

  public RouteDraftRequestDto elevationGain(@Nullable Integer elevationGain) {
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

  public RouteDraftRequestDto elevationLoss(@Nullable Integer elevationLoss) {
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

  public RouteDraftRequestDto groupId(@Nullable UUID groupId) {
    this.groupId = groupId;
    return this;
  }

  /**
   * Wyprawa (grupa) — wspólny identyfikator dni jednej wyprawy
   * @return groupId
   */
  @Valid 
  @Schema(name = "groupId", description = "Wyprawa (grupa) — wspólny identyfikator dni jednej wyprawy", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("groupId")
  public @Nullable UUID getGroupId() {
    return groupId;
  }

  @JsonProperty("groupId")
  public void setGroupId(@Nullable UUID groupId) {
    this.groupId = groupId;
  }

  public RouteDraftRequestDto groupName(@Nullable String groupName) {
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

  public RouteDraftRequestDto dayNumber(@Nullable Integer dayNumber) {
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

  public RouteDraftRequestDto waypoints(@Nullable String waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  /**
   * Waypointy (gminy) jako zakodowana polyline (OPAQUE) — do edycji wczytanego szkicu
   * @return waypoints
   */
  
  @Schema(name = "waypoints", description = "Waypointy (gminy) jako zakodowana polyline (OPAQUE) — do edycji wczytanego szkicu", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("waypoints")
  public @Nullable String getWaypoints() {
    return waypoints;
  }

  @JsonProperty("waypoints")
  public void setWaypoints(@Nullable String waypoints) {
    this.waypoints = waypoints;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RouteDraftRequestDto routeDraftRequest = (RouteDraftRequestDto) o;
    return Objects.equals(this.name, routeDraftRequest.name) &&
        Objects.equals(this.geometry, routeDraftRequest.geometry) &&
        Objects.equals(this.profile, routeDraftRequest.profile) &&
        Objects.equals(this.distanceKm, routeDraftRequest.distanceKm) &&
        Objects.equals(this.elevationGain, routeDraftRequest.elevationGain) &&
        Objects.equals(this.elevationLoss, routeDraftRequest.elevationLoss) &&
        Objects.equals(this.groupId, routeDraftRequest.groupId) &&
        Objects.equals(this.groupName, routeDraftRequest.groupName) &&
        Objects.equals(this.dayNumber, routeDraftRequest.dayNumber) &&
        Objects.equals(this.waypoints, routeDraftRequest.waypoints);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, geometry, profile, distanceKm, elevationGain, elevationLoss, groupId, groupName, dayNumber, waypoints);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RouteDraftRequestDto {\n");
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

