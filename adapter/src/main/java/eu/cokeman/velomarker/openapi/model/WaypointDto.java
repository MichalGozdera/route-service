package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * WaypointDto
 */

@JsonTypeName("Waypoint")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class WaypointDto {

  private Double lng;

  private Double lat;

  private @Nullable String name = null;

  public WaypointDto() {
    super();
  }

  public WaypointDto lng(Double lng) {
    this.lng = lng;
    return this;
  }

  /**
   * Get lng
   * @return lng
   */
  @NotNull 
  @Schema(name = "lng", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("lng")
  public Double getLng() {
    return lng;
  }

  @JsonProperty("lng")
  public void setLng(Double lng) {
    this.lng = lng;
  }

  public WaypointDto lat(Double lat) {
    this.lat = lat;
    return this;
  }

  /**
   * Get lat
   * @return lat
   */
  @NotNull 
  @Schema(name = "lat", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("lat")
  public Double getLat() {
    return lat;
  }

  @JsonProperty("lat")
  public void setLat(Double lat) {
    this.lat = lat;
  }

  public WaypointDto name(@Nullable String name) {
    this.name = name;
    return this;
  }

  /**
   * Get name
   * @return name
   */
  
  @Schema(name = "name", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("name")
  public @Nullable String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(@Nullable String name) {
    this.name = name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    WaypointDto waypoint = (WaypointDto) o;
    return Objects.equals(this.lng, waypoint.lng) &&
        Objects.equals(this.lat, waypoint.lat) &&
        Objects.equals(this.name, waypoint.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(lng, lat, name);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class WaypointDto {\n");
    sb.append("    lng: ").append(toIndentedString(lng)).append("\n");
    sb.append("    lat: ").append(toIndentedString(lat)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
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

