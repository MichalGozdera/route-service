package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.RouteStatsDto;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * RouteDraftGroupGeometryItemDto
 */

@JsonTypeName("RouteDraftGroupGeometryItem")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RouteDraftGroupGeometryItemDto {

  private Integer dayNumber;

  private String geometryEncoded;

  private @Nullable Double distanceKm = null;

  private @Nullable RouteStatsDto stats;

  public RouteDraftGroupGeometryItemDto() {
    super();
  }

  public RouteDraftGroupGeometryItemDto dayNumber(Integer dayNumber) {
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

  public RouteDraftGroupGeometryItemDto geometryEncoded(String geometryEncoded) {
    this.geometryEncoded = geometryEncoded;
    return this;
  }

  /**
   * Polyline3DCodec encoded geometry (lng,lat,z) — frontend decodes via decodePolyline3D
   * @return geometryEncoded
   */
  @NotNull 
  @Schema(name = "geometryEncoded", description = "Polyline3DCodec encoded geometry (lng,lat,z) — frontend decodes via decodePolyline3D", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("geometryEncoded")
  public String getGeometryEncoded() {
    return geometryEncoded;
  }

  @JsonProperty("geometryEncoded")
  public void setGeometryEncoded(String geometryEncoded) {
    this.geometryEncoded = geometryEncoded;
  }

  public RouteDraftGroupGeometryItemDto distanceKm(@Nullable Double distanceKm) {
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

  public RouteDraftGroupGeometryItemDto stats(@Nullable RouteStatsDto stats) {
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
    RouteDraftGroupGeometryItemDto routeDraftGroupGeometryItem = (RouteDraftGroupGeometryItemDto) o;
    return Objects.equals(this.dayNumber, routeDraftGroupGeometryItem.dayNumber) &&
        Objects.equals(this.geometryEncoded, routeDraftGroupGeometryItem.geometryEncoded) &&
        Objects.equals(this.distanceKm, routeDraftGroupGeometryItem.distanceKm) &&
        Objects.equals(this.stats, routeDraftGroupGeometryItem.stats);
  }

  @Override
  public int hashCode() {
    return Objects.hash(dayNumber, geometryEncoded, distanceKm, stats);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RouteDraftGroupGeometryItemDto {\n");
    sb.append("    dayNumber: ").append(toIndentedString(dayNumber)).append("\n");
    sb.append("    geometryEncoded: ").append(toIndentedString(geometryEncoded)).append("\n");
    sb.append("    distanceKm: ").append(toIndentedString(distanceKm)).append("\n");
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

