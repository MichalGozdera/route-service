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
 * Slippy-map tile coordinate (x,y) on the chosen zoom level.
 */

@Schema(name = "TileXY", description = "Slippy-map tile coordinate (x,y) on the chosen zoom level.")
@JsonTypeName("TileXY")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class TileXYDto {

  private Integer x;

  private Integer y;

  public TileXYDto() {
    super();
  }

  public TileXYDto x(Integer x) {
    this.x = x;
    return this;
  }

  /**
   * Get x
   * @return x
   */
  @NotNull 
  @Schema(name = "x", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("x")
  public Integer getX() {
    return x;
  }

  @JsonProperty("x")
  public void setX(Integer x) {
    this.x = x;
  }

  public TileXYDto y(Integer y) {
    this.y = y;
    return this;
  }

  /**
   * Get y
   * @return y
   */
  @NotNull 
  @Schema(name = "y", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("y")
  public Integer getY() {
    return y;
  }

  @JsonProperty("y")
  public void setY(Integer y) {
    this.y = y;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TileXYDto tileXY = (TileXYDto) o;
    return Objects.equals(this.x, tileXY.x) &&
        Objects.equals(this.y, tileXY.y);
  }

  @Override
  public int hashCode() {
    return Objects.hash(x, y);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TileXYDto {\n");
    sb.append("    x: ").append(toIndentedString(x)).append("\n");
    sb.append("    y: ").append(toIndentedString(y)).append("\n");
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

