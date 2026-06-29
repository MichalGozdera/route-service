package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.RouteStatsDto;
import eu.cokeman.velomarker.openapi.model.WaypointDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Snapshot trasy manualnej wysyłany przy KOŃCZENIU planowania. Front liczy geometrię 3D (z Z), gain/loss i scalony stats — backend tylko przechowuje (bez re-routingu/próbkowania DEM). 
 */

@Schema(name = "ManualSessionUpsertRequest", description = "Snapshot trasy manualnej wysyłany przy KOŃCZENIU planowania. Front liczy geometrię 3D (z Z), gain/loss i scalony stats — backend tylko przechowuje (bez re-routingu/próbkowania DEM). ")
@JsonTypeName("ManualSessionUpsertRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class ManualSessionUpsertRequestDto {

  private String geometryEncoded;

  @Valid
  private List<@Valid WaypointDto> waypoints = new ArrayList<>();

  private String profile;

  private @Nullable Double distanceKm = null;

  private @Nullable Integer elevationGain = null;

  private @Nullable Integer elevationLoss = null;

  private @Nullable RouteStatsDto stats;

  public ManualSessionUpsertRequestDto() {
    super();
  }

  public ManualSessionUpsertRequestDto geometryEncoded(String geometryEncoded) {
    this.geometryEncoded = geometryEncoded;
    return this;
  }

  /**
   * Polyline3DCodec encoded (lng,lat,z); 2D dopuszczalne (z=0) gdy brak profilu.
   * @return geometryEncoded
   */
  @NotNull 
  @Schema(name = "geometryEncoded", description = "Polyline3DCodec encoded (lng,lat,z); 2D dopuszczalne (z=0) gdy brak profilu.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("geometryEncoded")
  public String getGeometryEncoded() {
    return geometryEncoded;
  }

  @JsonProperty("geometryEncoded")
  public void setGeometryEncoded(String geometryEncoded) {
    this.geometryEncoded = geometryEncoded;
  }

  public ManualSessionUpsertRequestDto waypoints(List<@Valid WaypointDto> waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  public ManualSessionUpsertRequestDto addWaypointsItem(WaypointDto waypointsItem) {
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

  public ManualSessionUpsertRequestDto profile(String profile) {
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

  public ManualSessionUpsertRequestDto distanceKm(@Nullable Double distanceKm) {
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

  public ManualSessionUpsertRequestDto elevationGain(@Nullable Integer elevationGain) {
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

  public ManualSessionUpsertRequestDto elevationLoss(@Nullable Integer elevationLoss) {
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

  public ManualSessionUpsertRequestDto stats(@Nullable RouteStatsDto stats) {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ManualSessionUpsertRequestDto manualSessionUpsertRequest = (ManualSessionUpsertRequestDto) o;
    return Objects.equals(this.geometryEncoded, manualSessionUpsertRequest.geometryEncoded) &&
        Objects.equals(this.waypoints, manualSessionUpsertRequest.waypoints) &&
        Objects.equals(this.profile, manualSessionUpsertRequest.profile) &&
        Objects.equals(this.distanceKm, manualSessionUpsertRequest.distanceKm) &&
        Objects.equals(this.elevationGain, manualSessionUpsertRequest.elevationGain) &&
        Objects.equals(this.elevationLoss, manualSessionUpsertRequest.elevationLoss) &&
        Objects.equals(this.stats, manualSessionUpsertRequest.stats);
  }

  @Override
  public int hashCode() {
    return Objects.hash(geometryEncoded, waypoints, profile, distanceKm, elevationGain, elevationLoss, stats);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ManualSessionUpsertRequestDto {\n");
    sb.append("    geometryEncoded: ").append(toIndentedString(geometryEncoded)).append("\n");
    sb.append("    waypoints: ").append(toIndentedString(waypoints)).append("\n");
    sb.append("    profile: ").append(toIndentedString(profile)).append("\n");
    sb.append("    distanceKm: ").append(toIndentedString(distanceKm)).append("\n");
    sb.append("    elevationGain: ").append(toIndentedString(elevationGain)).append("\n");
    sb.append("    elevationLoss: ").append(toIndentedString(elevationLoss)).append("\n");
    sb.append("    stats: ").append(toIndentedString(stats)).append("\n");
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

