package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
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
 * CalculateRouteRequestDto
 */

@JsonTypeName("CalculateRouteRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class CalculateRouteRequestDto {

  @Valid
  private List<List<Double>> waypoints = new ArrayList<>();

  private String profile;

  public CalculateRouteRequestDto() {
    super();
  }

  public CalculateRouteRequestDto waypoints(List<List<Double>> waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  public CalculateRouteRequestDto addWaypointsItem(List<Double> waypointsItem) {
    if (this.waypoints == null) {
      this.waypoints = new ArrayList<>();
    }
    this.waypoints.add(waypointsItem);
    return this;
  }

  /**
   * Ordered list of [lng, lat] pairs (min 2, max 50)
   * @return waypoints
   */
  @NotNull @Valid @Size(min = 2, max = 50) 
  @Schema(name = "waypoints", example = "[[21.0,52.2],[21.5,52.5]]", description = "Ordered list of [lng, lat] pairs (min 2, max 50)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("waypoints")
  public List<List<Double>> getWaypoints() {
    return waypoints;
  }

  @JsonProperty("waypoints")
  public void setWaypoints(List<List<Double>> waypoints) {
    this.waypoints = waypoints;
  }

  public CalculateRouteRequestDto profile(String profile) {
    this.profile = profile;
    return this;
  }

  /**
   * BRouter profile name (without .brf suffix)
   * @return profile
   */
  @NotNull 
  @Schema(name = "profile", example = "fastbike", description = "BRouter profile name (without .brf suffix)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("profile")
  public String getProfile() {
    return profile;
  }

  @JsonProperty("profile")
  public void setProfile(String profile) {
    this.profile = profile;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CalculateRouteRequestDto calculateRouteRequest = (CalculateRouteRequestDto) o;
    return Objects.equals(this.waypoints, calculateRouteRequest.waypoints) &&
        Objects.equals(this.profile, calculateRouteRequest.profile);
  }

  @Override
  public int hashCode() {
    return Objects.hash(waypoints, profile);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CalculateRouteRequestDto {\n");
    sb.append("    waypoints: ").append(toIndentedString(waypoints)).append("\n");
    sb.append("    profile: ").append(toIndentedString(profile)).append("\n");
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

