package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
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
 * UpdatePlanningDayRequestDto
 */

@JsonTypeName("UpdatePlanningDayRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class UpdatePlanningDayRequestDto {

  @Valid
  private List<@Valid WaypointDto> waypoints = new ArrayList<>();

  public UpdatePlanningDayRequestDto() {
    super();
  }

  public UpdatePlanningDayRequestDto waypoints(List<@Valid WaypointDto> waypoints) {
    this.waypoints = waypoints;
    return this;
  }

  public UpdatePlanningDayRequestDto addWaypointsItem(WaypointDto waypointsItem) {
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
  @NotNull @Valid @Size(min = 2) 
  @Schema(name = "waypoints", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("waypoints")
  public List<@Valid WaypointDto> getWaypoints() {
    return waypoints;
  }

  @JsonProperty("waypoints")
  public void setWaypoints(List<@Valid WaypointDto> waypoints) {
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
    UpdatePlanningDayRequestDto updatePlanningDayRequest = (UpdatePlanningDayRequestDto) o;
    return Objects.equals(this.waypoints, updatePlanningDayRequest.waypoints);
  }

  @Override
  public int hashCode() {
    return Objects.hash(waypoints);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class UpdatePlanningDayRequestDto {\n");
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

