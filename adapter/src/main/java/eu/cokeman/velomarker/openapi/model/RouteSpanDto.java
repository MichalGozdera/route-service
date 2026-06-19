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
 * Zakres consecutive wierzchołków trasy z tym samym kodem kategorii. Indeksy lokalne dla &#x60;coordinates&#x60; z tego samego BRouter calle. 
 */

@Schema(name = "RouteSpan", description = "Zakres consecutive wierzchołków trasy z tym samym kodem kategorii. Indeksy lokalne dla `coordinates` z tego samego BRouter calle. ")
@JsonTypeName("RouteSpan")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RouteSpanDto {

  private Integer startIdx;

  private Integer endIdx;

  private String code;

  public RouteSpanDto() {
    super();
  }

  public RouteSpanDto startIdx(Integer startIdx) {
    this.startIdx = startIdx;
    return this;
  }

  /**
   * Indeks pierwszego wierzchołka spanu (włącznie)
   * @return startIdx
   */
  @NotNull 
  @Schema(name = "startIdx", description = "Indeks pierwszego wierzchołka spanu (włącznie)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("startIdx")
  public Integer getStartIdx() {
    return startIdx;
  }

  @JsonProperty("startIdx")
  public void setStartIdx(Integer startIdx) {
    this.startIdx = startIdx;
  }

  public RouteSpanDto endIdx(Integer endIdx) {
    this.endIdx = endIdx;
    return this;
  }

  /**
   * Indeks ostatniego wierzchołka spanu (włącznie)
   * @return endIdx
   */
  @NotNull 
  @Schema(name = "endIdx", description = "Indeks ostatniego wierzchołka spanu (włącznie)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("endIdx")
  public Integer getEndIdx() {
    return endIdx;
  }

  @JsonProperty("endIdx")
  public void setEndIdx(Integer endIdx) {
    this.endIdx = endIdx;
  }

  public RouteSpanDto code(String code) {
    this.code = code;
    return this;
  }

  /**
   * Znormalizowany kod kategorii (zgodny z RouteStats.surfaceMeters/roadMeters/smoothnessMeters)
   * @return code
   */
  @NotNull 
  @Schema(name = "code", description = "Znormalizowany kod kategorii (zgodny z RouteStats.surfaceMeters/roadMeters/smoothnessMeters)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("code")
  public String getCode() {
    return code;
  }

  @JsonProperty("code")
  public void setCode(String code) {
    this.code = code;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RouteSpanDto routeSpan = (RouteSpanDto) o;
    return Objects.equals(this.startIdx, routeSpan.startIdx) &&
        Objects.equals(this.endIdx, routeSpan.endIdx) &&
        Objects.equals(this.code, routeSpan.code);
  }

  @Override
  public int hashCode() {
    return Objects.hash(startIdx, endIdx, code);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RouteSpanDto {\n");
    sb.append("    startIdx: ").append(toIndentedString(startIdx)).append("\n");
    sb.append("    endIdx: ").append(toIndentedString(endIdx)).append("\n");
    sb.append("    code: ").append(toIndentedString(code)).append("\n");
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

