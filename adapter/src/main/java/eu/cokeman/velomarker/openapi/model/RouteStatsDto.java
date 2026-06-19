package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import eu.cokeman.velomarker.openapi.model.RouteSpanDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Statystyki tej trasy (pojedynczy /route/calculate call &#x3D; jeden \&quot;leg\&quot; między dwoma waypointami). Klucze map to ZNORMALIZOWANE KODY (stable identifiers), nie ludzkie etykiety — klient tłumaczy je na lokalny język przez własne i18n maps (PL/EN/DE/FR/...). Wartości w metrach. Klient agreguje (sumuje wartości per klucz) dla całej trasy multi-waypoint.  Spans (surfaceSpans / roadSpans / smoothnessSpans) pozwalają FE kolorować linię na mapie zależnie od aktywnego filtra. Indeksy w spans są LOKALNE dla &#x60;coordinates&#x60; z tego calle. 
 */

@Schema(name = "RouteStats", description = "Statystyki tej trasy (pojedynczy /route/calculate call = jeden \"leg\" między dwoma waypointami). Klucze map to ZNORMALIZOWANE KODY (stable identifiers), nie ludzkie etykiety — klient tłumaczy je na lokalny język przez własne i18n maps (PL/EN/DE/FR/...). Wartości w metrach. Klient agreguje (sumuje wartości per klucz) dla całej trasy multi-waypoint.  Spans (surfaceSpans / roadSpans / smoothnessSpans) pozwalają FE kolorować linię na mapie zależnie od aktywnego filtra. Indeksy w spans są LOKALNE dla `coordinates` z tego calle. ")
@JsonTypeName("RouteStats")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class RouteStatsDto {

  private Long totalMeters;

  @Valid
  private Map<String, Long> surfaceMeters = new HashMap<>();

  @Valid
  private Map<String, Long> roadMeters = new HashMap<>();

  @Valid
  private Map<String, Long> smoothnessMeters = new HashMap<>();

  @Valid
  private List<@Valid RouteSpanDto> surfaceSpans = new ArrayList<>();

  @Valid
  private List<@Valid RouteSpanDto> roadSpans = new ArrayList<>();

  @Valid
  private List<@Valid RouteSpanDto> smoothnessSpans = new ArrayList<>();

  public RouteStatsDto() {
    super();
  }

  public RouteStatsDto totalMeters(Long totalMeters) {
    this.totalMeters = totalMeters;
    return this;
  }

  /**
   * Suma długości segmentów (≈ distanceKm * 1000)
   * @return totalMeters
   */
  @NotNull 
  @Schema(name = "totalMeters", description = "Suma długości segmentów (≈ distanceKm * 1000)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("totalMeters")
  public Long getTotalMeters() {
    return totalMeters;
  }

  @JsonProperty("totalMeters")
  public void setTotalMeters(Long totalMeters) {
    this.totalMeters = totalMeters;
  }

  public RouteStatsDto surfaceMeters(Map<String, Long> surfaceMeters) {
    this.surfaceMeters = surfaceMeters;
    return this;
  }

  public RouteStatsDto putSurfaceMetersItem(String key, Long surfaceMetersItem) {
    if (this.surfaceMeters == null) {
      this.surfaceMeters = new HashMap<>();
    }
    this.surfaceMeters.put(key, surfaceMetersItem);
    return this;
  }

  /**
   * Typy nawierzchni → metry. Klucze to raw OSM tagi lowercase (asphalt, paving_stones, sett, cobblestone, concrete, gravel, fine_gravel, compacted, unpaved, ground, dirt, grass, sand, ...) lub \"unknown\" dla segmentów bez surface=*. 
   * @return surfaceMeters
   */
  @NotNull 
  @Schema(name = "surfaceMeters", description = "Typy nawierzchni → metry. Klucze to raw OSM tagi lowercase (asphalt, paving_stones, sett, cobblestone, concrete, gravel, fine_gravel, compacted, unpaved, ground, dirt, grass, sand, ...) lub \"unknown\" dla segmentów bez surface=*. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("surfaceMeters")
  public Map<String, Long> getSurfaceMeters() {
    return surfaceMeters;
  }

  @JsonProperty("surfaceMeters")
  public void setSurfaceMeters(Map<String, Long> surfaceMeters) {
    this.surfaceMeters = surfaceMeters;
  }

  public RouteStatsDto roadMeters(Map<String, Long> roadMeters) {
    this.roadMeters = roadMeters;
    return this;
  }

  public RouteStatsDto putRoadMetersItem(String key, Long roadMetersItem) {
    if (this.roadMeters == null) {
      this.roadMeters = new HashMap<>();
    }
    this.roadMeters.put(key, roadMetersItem);
    return this;
  }

  /**
   * Typy dróg → metry. Klucze to znormalizowane kody: - Drogi publiczne: motorway, trunk, primary, secondary, tertiary, unclassified,   residential, living_street, service. - Z optymalnym suffixem: _with_cycleway_lane, _use_sidepath. - Z optymalnym refem po dwukropku: \":REF\" (np. \"primary:DK7\", \"secondary:D38\",   \"primary_with_cycleway_lane:N7\"). Działa dla DK7 (PL), D38 (FR), N100 (CH), A1 (UK). - Ścieżki/chodniki: cycleway, cycleway_shared_foot, path_bike_foot, path_bike,   path_foot, path, footway, footway_bike_allowed, pedestrian, pedestrian_bike_allowed,   track, bridleway, steps. - \"unknown\" dla segmentów bez highway=*. 
   * @return roadMeters
   */
  @NotNull 
  @Schema(name = "roadMeters", description = "Typy dróg → metry. Klucze to znormalizowane kody: - Drogi publiczne: motorway, trunk, primary, secondary, tertiary, unclassified,   residential, living_street, service. - Z optymalnym suffixem: _with_cycleway_lane, _use_sidepath. - Z optymalnym refem po dwukropku: \":REF\" (np. \"primary:DK7\", \"secondary:D38\",   \"primary_with_cycleway_lane:N7\"). Działa dla DK7 (PL), D38 (FR), N100 (CH), A1 (UK). - Ścieżki/chodniki: cycleway, cycleway_shared_foot, path_bike_foot, path_bike,   path_foot, path, footway, footway_bike_allowed, pedestrian, pedestrian_bike_allowed,   track, bridleway, steps. - \"unknown\" dla segmentów bez highway=*. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("roadMeters")
  public Map<String, Long> getRoadMeters() {
    return roadMeters;
  }

  @JsonProperty("roadMeters")
  public void setRoadMeters(Map<String, Long> roadMeters) {
    this.roadMeters = roadMeters;
  }

  public RouteStatsDto smoothnessMeters(Map<String, Long> smoothnessMeters) {
    this.smoothnessMeters = smoothnessMeters;
    return this;
  }

  public RouteStatsDto putSmoothnessMetersItem(String key, Long smoothnessMetersItem) {
    if (this.smoothnessMeters == null) {
      this.smoothnessMeters = new HashMap<>();
    }
    this.smoothnessMeters.put(key, smoothnessMetersItem);
    return this;
  }

  /**
   * Jakość nawierzchni → metry. Klucze to raw OSM tagi lowercase: excellent, good, intermediate, bad, very_bad, horrible, very_horrible, impassable, lub \"unknown\". 
   * @return smoothnessMeters
   */
  @NotNull 
  @Schema(name = "smoothnessMeters", description = "Jakość nawierzchni → metry. Klucze to raw OSM tagi lowercase: excellent, good, intermediate, bad, very_bad, horrible, very_horrible, impassable, lub \"unknown\". ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("smoothnessMeters")
  public Map<String, Long> getSmoothnessMeters() {
    return smoothnessMeters;
  }

  @JsonProperty("smoothnessMeters")
  public void setSmoothnessMeters(Map<String, Long> smoothnessMeters) {
    this.smoothnessMeters = smoothnessMeters;
  }

  public RouteStatsDto surfaceSpans(List<@Valid RouteSpanDto> surfaceSpans) {
    this.surfaceSpans = surfaceSpans;
    return this;
  }

  public RouteStatsDto addSurfaceSpansItem(RouteSpanDto surfaceSpansItem) {
    if (this.surfaceSpans == null) {
      this.surfaceSpans = new ArrayList<>();
    }
    this.surfaceSpans.add(surfaceSpansItem);
    return this;
  }

  /**
   * Zakresy [startIdx, endIdx] wierzchołków `coordinates` z tym samym surface code. Consecutive spans z tym samym kodem są zmergowane. Lista pusta gdy stats agregowane z wielu calls (accumulator) — indeksy nie mają wtedy sensu. 
   * @return surfaceSpans
   */
  @NotNull @Valid 
  @Schema(name = "surfaceSpans", description = "Zakresy [startIdx, endIdx] wierzchołków `coordinates` z tym samym surface code. Consecutive spans z tym samym kodem są zmergowane. Lista pusta gdy stats agregowane z wielu calls (accumulator) — indeksy nie mają wtedy sensu. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("surfaceSpans")
  public List<@Valid RouteSpanDto> getSurfaceSpans() {
    return surfaceSpans;
  }

  @JsonProperty("surfaceSpans")
  public void setSurfaceSpans(List<@Valid RouteSpanDto> surfaceSpans) {
    this.surfaceSpans = surfaceSpans;
  }

  public RouteStatsDto roadSpans(List<@Valid RouteSpanDto> roadSpans) {
    this.roadSpans = roadSpans;
    return this;
  }

  public RouteStatsDto addRoadSpansItem(RouteSpanDto roadSpansItem) {
    if (this.roadSpans == null) {
      this.roadSpans = new ArrayList<>();
    }
    this.roadSpans.add(roadSpansItem);
    return this;
  }

  /**
   * Jak surfaceSpans, dla road code.
   * @return roadSpans
   */
  @NotNull @Valid 
  @Schema(name = "roadSpans", description = "Jak surfaceSpans, dla road code.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("roadSpans")
  public List<@Valid RouteSpanDto> getRoadSpans() {
    return roadSpans;
  }

  @JsonProperty("roadSpans")
  public void setRoadSpans(List<@Valid RouteSpanDto> roadSpans) {
    this.roadSpans = roadSpans;
  }

  public RouteStatsDto smoothnessSpans(List<@Valid RouteSpanDto> smoothnessSpans) {
    this.smoothnessSpans = smoothnessSpans;
    return this;
  }

  public RouteStatsDto addSmoothnessSpansItem(RouteSpanDto smoothnessSpansItem) {
    if (this.smoothnessSpans == null) {
      this.smoothnessSpans = new ArrayList<>();
    }
    this.smoothnessSpans.add(smoothnessSpansItem);
    return this;
  }

  /**
   * Jak surfaceSpans, dla smoothness code.
   * @return smoothnessSpans
   */
  @NotNull @Valid 
  @Schema(name = "smoothnessSpans", description = "Jak surfaceSpans, dla smoothness code.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("smoothnessSpans")
  public List<@Valid RouteSpanDto> getSmoothnessSpans() {
    return smoothnessSpans;
  }

  @JsonProperty("smoothnessSpans")
  public void setSmoothnessSpans(List<@Valid RouteSpanDto> smoothnessSpans) {
    this.smoothnessSpans = smoothnessSpans;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RouteStatsDto routeStats = (RouteStatsDto) o;
    return Objects.equals(this.totalMeters, routeStats.totalMeters) &&
        Objects.equals(this.surfaceMeters, routeStats.surfaceMeters) &&
        Objects.equals(this.roadMeters, routeStats.roadMeters) &&
        Objects.equals(this.smoothnessMeters, routeStats.smoothnessMeters) &&
        Objects.equals(this.surfaceSpans, routeStats.surfaceSpans) &&
        Objects.equals(this.roadSpans, routeStats.roadSpans) &&
        Objects.equals(this.smoothnessSpans, routeStats.smoothnessSpans);
  }

  @Override
  public int hashCode() {
    return Objects.hash(totalMeters, surfaceMeters, roadMeters, smoothnessMeters, surfaceSpans, roadSpans, smoothnessSpans);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RouteStatsDto {\n");
    sb.append("    totalMeters: ").append(toIndentedString(totalMeters)).append("\n");
    sb.append("    surfaceMeters: ").append(toIndentedString(surfaceMeters)).append("\n");
    sb.append("    roadMeters: ").append(toIndentedString(roadMeters)).append("\n");
    sb.append("    smoothnessMeters: ").append(toIndentedString(smoothnessMeters)).append("\n");
    sb.append("    surfaceSpans: ").append(toIndentedString(surfaceSpans)).append("\n");
    sb.append("    roadSpans: ").append(toIndentedString(roadSpans)).append("\n");
    sb.append("    smoothnessSpans: ").append(toIndentedString(smoothnessSpans)).append("\n");
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

