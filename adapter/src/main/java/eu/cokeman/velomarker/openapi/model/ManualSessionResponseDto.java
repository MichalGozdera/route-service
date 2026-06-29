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
 * Ostatnia trasa manualna usera (jedna per user). Geometria 3D &#x3D; natychmiastowy profil wysokości.
 */

@Schema(name = "ManualSessionResponse", description = "Ostatnia trasa manualna usera (jedna per user). Geometria 3D = natychmiastowy profil wysokości.")
@JsonTypeName("ManualSessionResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class ManualSessionResponseDto {

  private UUID id;

  private UUID userId;

  private String geometryEncoded;

  @Valid
  private List<@Valid WaypointDto> waypoints = new ArrayList<>();

  private @Nullable Double distanceKm = null;

  private @Nullable Integer elevationGain = null;

  private @Nullable Integer elevationLoss = null;

  private String profile;

  private @Nullable RouteStatsDto stats;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant editedAt;

  public ManualSessionResponseDto() {
    super();
  }

  public ManualSessionResponseDto id(UUID id) {
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

  public ManualSessionResponseDto userId(UUID userId) {
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

  public ManualSessionResponseDto geometryEncoded(String geometryEncoded) {
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

  public ManualSessionResponseDto waypoints(List<@Valid WaypointDto> waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  public ManualSessionResponseDto addWaypointsItem(WaypointDto waypointsItem) {
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

  public ManualSessionResponseDto distanceKm(@Nullable Double distanceKm) {
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

  public ManualSessionResponseDto elevationGain(@Nullable Integer elevationGain) {
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

  public ManualSessionResponseDto elevationLoss(@Nullable Integer elevationLoss) {
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

  public ManualSessionResponseDto profile(String profile) {
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

  public ManualSessionResponseDto stats(@Nullable RouteStatsDto stats) {
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

  public ManualSessionResponseDto editedAt(Instant editedAt) {
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
    ManualSessionResponseDto manualSessionResponse = (ManualSessionResponseDto) o;
    return Objects.equals(this.id, manualSessionResponse.id) &&
        Objects.equals(this.userId, manualSessionResponse.userId) &&
        Objects.equals(this.geometryEncoded, manualSessionResponse.geometryEncoded) &&
        Objects.equals(this.waypoints, manualSessionResponse.waypoints) &&
        Objects.equals(this.distanceKm, manualSessionResponse.distanceKm) &&
        Objects.equals(this.elevationGain, manualSessionResponse.elevationGain) &&
        Objects.equals(this.elevationLoss, manualSessionResponse.elevationLoss) &&
        Objects.equals(this.profile, manualSessionResponse.profile) &&
        Objects.equals(this.stats, manualSessionResponse.stats) &&
        Objects.equals(this.editedAt, manualSessionResponse.editedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, userId, geometryEncoded, waypoints, distanceKm, elevationGain, elevationLoss, profile, stats, editedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ManualSessionResponseDto {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
    sb.append("    geometryEncoded: ").append(toIndentedString(geometryEncoded)).append("\n");
    sb.append("    waypoints: ").append(toIndentedString(waypoints)).append("\n");
    sb.append("    distanceKm: ").append(toIndentedString(distanceKm)).append("\n");
    sb.append("    elevationGain: ").append(toIndentedString(elevationGain)).append("\n");
    sb.append("    elevationLoss: ").append(toIndentedString(elevationLoss)).append("\n");
    sb.append("    profile: ").append(toIndentedString(profile)).append("\n");
    sb.append("    stats: ").append(toIndentedString(stats)).append("\n");
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

