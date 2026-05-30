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
 * ElevationRequestDto
 */

@JsonTypeName("ElevationRequest")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class ElevationRequestDto {

  @Valid
  private List<List<Double>> coordinates = new ArrayList<>();

  public ElevationRequestDto() {
    super();
  }

  public ElevationRequestDto coordinates(List<List<Double>> coordinates) {
    this.coordinates = coordinates;
    return this;
  }

  public ElevationRequestDto addCoordinatesItem(List<Double> coordinatesItem) {
    if (this.coordinates == null) {
      this.coordinates = new ArrayList<>();
    }
    this.coordinates.add(coordinatesItem);
    return this;
  }

  /**
   * Ordered list of [lng, lat] pairs along the route
   * @return coordinates
   */
  @NotNull @Valid @Size(min = 2) 
  @Schema(name = "coordinates", description = "Ordered list of [lng, lat] pairs along the route", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("coordinates")
  public List<List<Double>> getCoordinates() {
    return coordinates;
  }

  @JsonProperty("coordinates")
  public void setCoordinates(List<List<Double>> coordinates) {
    this.coordinates = coordinates;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ElevationRequestDto elevationRequest = (ElevationRequestDto) o;
    return Objects.equals(this.coordinates, elevationRequest.coordinates);
  }

  @Override
  public int hashCode() {
    return Objects.hash(coordinates);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ElevationRequestDto {\n");
    sb.append("    coordinates: ").append(toIndentedString(coordinates)).append("\n");
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

