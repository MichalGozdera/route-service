package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import eu.cokeman.velomarker.openapi.model.TileXYDto;
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

  private @Nullable Boolean clearStart = null;

  private @Nullable Boolean clearEnd = null;

  private @Nullable Integer tileZoom = null;

  /**
   * TILES — optimisation objective. E1 treats missing as COVERAGE.
   */
  public enum TileObjectiveEnum {
    COVERAGE("COVERAGE"),
    
    SQUARE("SQUARE"),
    
    CLUSTER("CLUSTER");

    private final String value;

    TileObjectiveEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @JsonCreator
    public static TileObjectiveEnum fromValue(String value) {
      for (TileObjectiveEnum b : TileObjectiveEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      return null;
    }
  }

  private @Nullable TileObjectiveEnum tileObjective = null;

  @Valid
  private @Nullable List<@Valid TileXYDto> tileOwned;

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

  public RoutePreferencesDto clearStart(@Nullable Boolean clearStart) {
    this.clearStart = clearStart;
    return this;
  }

  /**
   * PATCH command — when true, clears (nulls) start. Command-only, never stored.
   * @return clearStart
   */
  
  @Schema(name = "clearStart", description = "PATCH command — when true, clears (nulls) start. Command-only, never stored.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("clearStart")
  public @Nullable Boolean getClearStart() {
    return clearStart;
  }

  @JsonProperty("clearStart")
  public void setClearStart(@Nullable Boolean clearStart) {
    this.clearStart = clearStart;
  }

  public RoutePreferencesDto clearEnd(@Nullable Boolean clearEnd) {
    this.clearEnd = clearEnd;
    return this;
  }

  /**
   * PATCH command — when true, clears (nulls) end. Command-only, never stored.
   * @return clearEnd
   */
  
  @Schema(name = "clearEnd", description = "PATCH command — when true, clears (nulls) end. Command-only, never stored.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("clearEnd")
  public @Nullable Boolean getClearEnd() {
    return clearEnd;
  }

  @JsonProperty("clearEnd")
  public void setClearEnd(@Nullable Boolean clearEnd) {
    this.clearEnd = clearEnd;
  }

  public RoutePreferencesDto tileZoom(@Nullable Integer tileZoom) {
    this.tileZoom = tileZoom;
    return this;
  }

  /**
   * TILES — slippy-map grid zoom level (typically 14).
   * minimum: 1
   * maximum: 17
   * @return tileZoom
   */
  @Min(value = 1) @Max(value = 17) 
  @Schema(name = "tileZoom", description = "TILES — slippy-map grid zoom level (typically 14).", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("tileZoom")
  public @Nullable Integer getTileZoom() {
    return tileZoom;
  }

  @JsonProperty("tileZoom")
  public void setTileZoom(@Nullable Integer tileZoom) {
    this.tileZoom = tileZoom;
  }

  public RoutePreferencesDto tileObjective(@Nullable TileObjectiveEnum tileObjective) {
    this.tileObjective = tileObjective;
    return this;
  }

  /**
   * TILES — optimisation objective. E1 treats missing as COVERAGE.
   * @return tileObjective
   */
  
  @Schema(name = "tileObjective", description = "TILES — optimisation objective. E1 treats missing as COVERAGE.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("tileObjective")
  public @Nullable TileObjectiveEnum getTileObjective() {
    return tileObjective;
  }

  @JsonProperty("tileObjective")
  public void setTileObjective(@Nullable TileObjectiveEnum tileObjective) {
    this.tileObjective = tileObjective;
  }

  public RoutePreferencesDto tileOwned(@Nullable List<@Valid TileXYDto> tileOwned) {
    this.tileOwned = tileOwned;
    return this;
  }

  public RoutePreferencesDto addTileOwnedItem(TileXYDto tileOwnedItem) {
    if (this.tileOwned == null) {
      this.tileOwned = new ArrayList<>();
    }
    this.tileOwned.add(tileOwnedItem);
    return this;
  }

  /**
   * TILES — already-owned tiles (adjacency/hole context, excluded from candidates).
   * @return tileOwned
   */
  @Valid 
  @Schema(name = "tileOwned", description = "TILES — already-owned tiles (adjacency/hole context, excluded from candidates).", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("tileOwned")
  public @Nullable List<@Valid TileXYDto> getTileOwned() {
    return tileOwned;
  }

  @JsonProperty("tileOwned")
  public void setTileOwned(@Nullable List<@Valid TileXYDto> tileOwned) {
    this.tileOwned = tileOwned;
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
        Objects.equals(this.profile, routePreferences.profile) &&
        Objects.equals(this.clearStart, routePreferences.clearStart) &&
        Objects.equals(this.clearEnd, routePreferences.clearEnd) &&
        Objects.equals(this.tileZoom, routePreferences.tileZoom) &&
        Objects.equals(this.tileObjective, routePreferences.tileObjective) &&
        Objects.equals(this.tileOwned, routePreferences.tileOwned);
  }

  @Override
  public int hashCode() {
    return Objects.hash(countryIds, levelIds, specialGroupIds, start, end, via, loop, days, kmPerDay, elevationPerDayM, profile, clearStart, clearEnd, tileZoom, tileObjective, tileOwned);
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
    sb.append("    clearStart: ").append(toIndentedString(clearStart)).append("\n");
    sb.append("    clearEnd: ").append(toIndentedString(clearEnd)).append("\n");
    sb.append("    tileZoom: ").append(toIndentedString(tileZoom)).append("\n");
    sb.append("    tileObjective: ").append(toIndentedString(tileObjective)).append("\n");
    sb.append("    tileOwned: ").append(toIndentedString(tileOwned)).append("\n");
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

