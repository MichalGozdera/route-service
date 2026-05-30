package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.LineStringGeoJsonDto;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * CalculateRouteResponseDto
 */

@JsonTypeName("CalculateRouteResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class CalculateRouteResponseDto {

  private LineStringGeoJsonDto geometry;

  private Double distanceKm;

  public CalculateRouteResponseDto() {
    super();
  }

  public CalculateRouteResponseDto geometry(LineStringGeoJsonDto geometry) {
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

  public CalculateRouteResponseDto distanceKm(Double distanceKm) {
    this.distanceKm = distanceKm;
    return this;
  }

  /**
   * Total route distance in kilometers
   * @return distanceKm
   */
  @NotNull 
  @Schema(name = "distanceKm", description = "Total route distance in kilometers", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("distanceKm")
  public Double getDistanceKm() {
    return distanceKm;
  }

  @JsonProperty("distanceKm")
  public void setDistanceKm(Double distanceKm) {
    this.distanceKm = distanceKm;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CalculateRouteResponseDto calculateRouteResponse = (CalculateRouteResponseDto) o;
    return Objects.equals(this.geometry, calculateRouteResponse.geometry) &&
        Objects.equals(this.distanceKm, calculateRouteResponse.distanceKm);
  }

  @Override
  public int hashCode() {
    return Objects.hash(geometry, distanceKm);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CalculateRouteResponseDto {\n");
    sb.append("    geometry: ").append(toIndentedString(geometry)).append("\n");
    sb.append("    distanceKm: ").append(toIndentedString(distanceKm)).append("\n");
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

