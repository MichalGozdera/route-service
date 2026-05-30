package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.RouteDraftGroupGeometryItemDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * RouteDraftGroupGeometriesResponseDto
 */

@JsonTypeName("RouteDraftGroupGeometriesResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RouteDraftGroupGeometriesResponseDto {

  private UUID groupId;

  @Valid
  private List<@Valid RouteDraftGroupGeometryItemDto> days = new ArrayList<>();

  public RouteDraftGroupGeometriesResponseDto() {
    super();
  }

  public RouteDraftGroupGeometriesResponseDto groupId(UUID groupId) {
    this.groupId = groupId;
    return this;
  }

  /**
   * Get groupId
   * @return groupId
   */
  @NotNull @Valid 
  @Schema(name = "groupId", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("groupId")
  public UUID getGroupId() {
    return groupId;
  }

  @JsonProperty("groupId")
  public void setGroupId(UUID groupId) {
    this.groupId = groupId;
  }

  public RouteDraftGroupGeometriesResponseDto days(List<@Valid RouteDraftGroupGeometryItemDto> days) {
    this.days = days;
    return this;
  }

  public RouteDraftGroupGeometriesResponseDto addDaysItem(RouteDraftGroupGeometryItemDto daysItem) {
    if (this.days == null) {
      this.days = new ArrayList<>();
    }
    this.days.add(daysItem);
    return this;
  }

  /**
   * Get days
   * @return days
   */
  @NotNull @Valid 
  @Schema(name = "days", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("days")
  public List<@Valid RouteDraftGroupGeometryItemDto> getDays() {
    return days;
  }

  @JsonProperty("days")
  public void setDays(List<@Valid RouteDraftGroupGeometryItemDto> days) {
    this.days = days;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RouteDraftGroupGeometriesResponseDto routeDraftGroupGeometriesResponse = (RouteDraftGroupGeometriesResponseDto) o;
    return Objects.equals(this.groupId, routeDraftGroupGeometriesResponse.groupId) &&
        Objects.equals(this.days, routeDraftGroupGeometriesResponse.days);
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, days);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RouteDraftGroupGeometriesResponseDto {\n");
    sb.append("    groupId: ").append(toIndentedString(groupId)).append("\n");
    sb.append("    days: ").append(toIndentedString(days)).append("\n");
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

