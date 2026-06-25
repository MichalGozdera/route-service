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
 * All fields nullable — PATCH semantics. Lists replace in full when provided.
 */

@Schema(name = "RoutePreferences", description = "All fields nullable — PATCH semantics. Lists replace in full when provided.")
@JsonTypeName("RoutePreferences")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RoutePreferencesDto {

  @Valid
  private @Nullable List<Integer> countryIds;

  @Valid
  private @Nullable List<Integer> levelIds;

  @Valid
  private @Nullable List<Integer> specialGroupIds;

  private @Nullable WaypointDto start;

  private @Nullable WaypointDto end;

  @Valid
  private @Nullable List<@Valid WaypointDto> via;

  private @Nullable Boolean loop = null;

  private @Nullable Integer days = null;

  private @Nullable Integer kmPerDay = null;

  private @Nullable Integer elevationPerDayM = null;

  private @Nullable String profile = null;

  public RoutePreferencesDto countryIds(@Nullable List<Integer> countryIds) {
    this.countryIds = countryIds;
    return this;
  }

  public RoutePreferencesDto addCountryIdsItem(Integer countryIdsItem) {
    if (this.countryIds == null) {
      this.countryIds = new ArrayList<>();
    }
    this.countryIds.add(countryIdsItem);
    return this;
  }

  /**
   * Get countryIds
   * @return countryIds
   */
  
  @Schema(name = "countryIds", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("countryIds")
  public @Nullable List<Integer> getCountryIds() {
    return countryIds;
  }

  @JsonProperty("countryIds")
  public void setCountryIds(@Nullable List<Integer> countryIds) {
    this.countryIds = countryIds;
  }

  public RoutePreferencesDto levelIds(@Nullable List<Integer> levelIds) {
    this.levelIds = levelIds;
    return this;
  }

  public RoutePreferencesDto addLevelIdsItem(Integer levelIdsItem) {
    if (this.levelIds == null) {
      this.levelIds = new ArrayList<>();
    }
    this.levelIds.add(levelIdsItem);
    return this;
  }

  /**
   * Get levelIds
   * @return levelIds
   */
  
  @Schema(name = "levelIds", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("levelIds")
  public @Nullable List<Integer> getLevelIds() {
    return levelIds;
  }

  @JsonProperty("levelIds")
  public void setLevelIds(@Nullable List<Integer> levelIds) {
    this.levelIds = levelIds;
  }

  public RoutePreferencesDto specialGroupIds(@Nullable List<Integer> specialGroupIds) {
    this.specialGroupIds = specialGroupIds;
    return this;
  }

  public RoutePreferencesDto addSpecialGroupIdsItem(Integer specialGroupIdsItem) {
    if (this.specialGroupIds == null) {
      this.specialGroupIds = new ArrayList<>();
    }
    this.specialGroupIds.add(specialGroupIdsItem);
    return this;
  }

  /**
   * Get specialGroupIds
   * @return specialGroupIds
   */
  
  @Schema(name = "specialGroupIds", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("specialGroupIds")
  public @Nullable List<Integer> getSpecialGroupIds() {
    return specialGroupIds;
  }

  @JsonProperty("specialGroupIds")
  public void setSpecialGroupIds(@Nullable List<Integer> specialGroupIds) {
    this.specialGroupIds = specialGroupIds;
  }

  public RoutePreferencesDto start(@Nullable WaypointDto start) {
    this.start = start;
    return this;
  }

  /**
   * Get start
   * @return start
   */
  @Valid 
  @Schema(name = "start", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("start")
  public @Nullable WaypointDto getStart() {
    return start;
  }

  @JsonProperty("start")
  public void setStart(@Nullable WaypointDto start) {
    this.start = start;
  }

  public RoutePreferencesDto end(@Nullable WaypointDto end) {
    this.end = end;
    return this;
  }

  /**
   * Get end
   * @return end
   */
  @Valid 
  @Schema(name = "end", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("end")
  public @Nullable WaypointDto getEnd() {
    return end;
  }

  @JsonProperty("end")
  public void setEnd(@Nullable WaypointDto end) {
    this.end = end;
  }

  public RoutePreferencesDto via(@Nullable List<@Valid WaypointDto> via) {
    this.via = via;
    return this;
  }

  public RoutePreferencesDto addViaItem(WaypointDto viaItem) {
    if (this.via == null) {
      this.via = new ArrayList<>();
    }
    this.via.add(viaItem);
    return this;
  }

  /**
   * Get via
   * @return via
   */
  @Valid 
  @Schema(name = "via", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("via")
  public @Nullable List<@Valid WaypointDto> getVia() {
    return via;
  }

  @JsonProperty("via")
  public void setVia(@Nullable List<@Valid WaypointDto> via) {
    this.via = via;
  }

  public RoutePreferencesDto loop(@Nullable Boolean loop) {
    this.loop = loop;
    return this;
  }

  /**
   * Get loop
   * @return loop
   */
  
  @Schema(name = "loop", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("loop")
  public @Nullable Boolean getLoop() {
    return loop;
  }

  @JsonProperty("loop")
  public void setLoop(@Nullable Boolean loop) {
    this.loop = loop;
  }

  public RoutePreferencesDto days(@Nullable Integer days) {
    this.days = days;
    return this;
  }

  /**
   * Get days
   * minimum: 1
   * maximum: 20000
   * @return days
   */
  @Min(value = 1) @Max(value = 20000) 
  @Schema(name = "days", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("days")
  public @Nullable Integer getDays() {
    return days;
  }

  @JsonProperty("days")
  public void setDays(@Nullable Integer days) {
    this.days = days;
  }

  public RoutePreferencesDto kmPerDay(@Nullable Integer kmPerDay) {
    this.kmPerDay = kmPerDay;
    return this;
  }

  /**
   * Get kmPerDay
   * minimum: 1
   * maximum: 1000
   * @return kmPerDay
   */
  @Min(value = 1) @Max(value = 1000) 
  @Schema(name = "kmPerDay", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("kmPerDay")
  public @Nullable Integer getKmPerDay() {
    return kmPerDay;
  }

  @JsonProperty("kmPerDay")
  public void setKmPerDay(@Nullable Integer kmPerDay) {
    this.kmPerDay = kmPerDay;
  }

  public RoutePreferencesDto elevationPerDayM(@Nullable Integer elevationPerDayM) {
    this.elevationPerDayM = elevationPerDayM;
    return this;
  }

  /**
   * Get elevationPerDayM
   * minimum: 0
   * maximum: 10000
   * @return elevationPerDayM
   */
  @Min(value = 0) @Max(value = 10000) 
  @Schema(name = "elevationPerDayM", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("elevationPerDayM")
  public @Nullable Integer getElevationPerDayM() {
    return elevationPerDayM;
  }

  @JsonProperty("elevationPerDayM")
  public void setElevationPerDayM(@Nullable Integer elevationPerDayM) {
    this.elevationPerDayM = elevationPerDayM;
  }

  public RoutePreferencesDto profile(@Nullable String profile) {
    this.profile = profile;
    return this;
  }

  /**
   * Direct BRouter profile name (fastbike/trekking/safety/fastbike-lowtraffic)
   * @return profile
   */
  
  @Schema(name = "profile", description = "Direct BRouter profile name (fastbike/trekking/safety/fastbike-lowtraffic)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("profile")
  public @Nullable String getProfile() {
    return profile;
  }

  @JsonProperty("profile")
  public void setProfile(@Nullable String profile) {
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
    RoutePreferencesDto routePreferences = (RoutePreferencesDto) o;
    return Objects.equals(this.countryIds, routePreferences.countryIds) &&
        Objects.equals(this.levelIds, routePreferences.levelIds) &&
        Objects.equals(this.specialGroupIds, routePreferences.specialGroupIds) &&
        Objects.equals(this.start, routePreferences.start) &&
        Objects.equals(this.end, routePreferences.end) &&
        Objects.equals(this.via, routePreferences.via) &&
        Objects.equals(this.loop, routePreferences.loop) &&
        Objects.equals(this.days, routePreferences.days) &&
        Objects.equals(this.kmPerDay, routePreferences.kmPerDay) &&
        Objects.equals(this.elevationPerDayM, routePreferences.elevationPerDayM) &&
        Objects.equals(this.profile, routePreferences.profile);
  }

  @Override
  public int hashCode() {
    return Objects.hash(countryIds, levelIds, specialGroupIds, start, end, via, loop, days, kmPerDay, elevationPerDayM, profile);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RoutePreferencesDto {\n");
    sb.append("    countryIds: ").append(toIndentedString(countryIds)).append("\n");
    sb.append("    levelIds: ").append(toIndentedString(levelIds)).append("\n");
    sb.append("    specialGroupIds: ").append(toIndentedString(specialGroupIds)).append("\n");
    sb.append("    start: ").append(toIndentedString(start)).append("\n");
    sb.append("    end: ").append(toIndentedString(end)).append("\n");
    sb.append("    via: ").append(toIndentedString(via)).append("\n");
    sb.append("    loop: ").append(toIndentedString(loop)).append("\n");
    sb.append("    days: ").append(toIndentedString(days)).append("\n");
    sb.append("    kmPerDay: ").append(toIndentedString(kmPerDay)).append("\n");
    sb.append("    elevationPerDayM: ").append(toIndentedString(elevationPerDayM)).append("\n");
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

