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
 * ElevationResponseDto
 */

@JsonTypeName("ElevationResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class ElevationResponseDto {

  @Valid
  private List<List<Double>> profile = new ArrayList<>();

  private Integer gainM;

  private Integer lossM;

  private Integer minEleM;

  private Integer maxEleM;

  public ElevationResponseDto() {
    super();
  }

  public ElevationResponseDto profile(List<List<Double>> profile) {
    this.profile = profile;
    return this;
  }

  public ElevationResponseDto addProfileItem(List<Double> profileItem) {
    if (this.profile == null) {
      this.profile = new ArrayList<>();
    }
    this.profile.add(profileItem);
    return this;
  }

  /**
   * Sampled elevation profile, each entry [distanceFromStartMeters, elevationMeters]
   * @return profile
   */
  @NotNull @Valid 
  @Schema(name = "profile", description = "Sampled elevation profile, each entry [distanceFromStartMeters, elevationMeters]", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("profile")
  public List<List<Double>> getProfile() {
    return profile;
  }

  @JsonProperty("profile")
  public void setProfile(List<List<Double>> profile) {
    this.profile = profile;
  }

  public ElevationResponseDto gainM(Integer gainM) {
    this.gainM = gainM;
    return this;
  }

  /**
   * Total elevation gain in meters
   * @return gainM
   */
  @NotNull 
  @Schema(name = "gainM", description = "Total elevation gain in meters", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("gainM")
  public Integer getGainM() {
    return gainM;
  }

  @JsonProperty("gainM")
  public void setGainM(Integer gainM) {
    this.gainM = gainM;
  }

  public ElevationResponseDto lossM(Integer lossM) {
    this.lossM = lossM;
    return this;
  }

  /**
   * Total elevation loss in meters
   * @return lossM
   */
  @NotNull 
  @Schema(name = "lossM", description = "Total elevation loss in meters", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("lossM")
  public Integer getLossM() {
    return lossM;
  }

  @JsonProperty("lossM")
  public void setLossM(Integer lossM) {
    this.lossM = lossM;
  }

  public ElevationResponseDto minEleM(Integer minEleM) {
    this.minEleM = minEleM;
    return this;
  }

  /**
   * Get minEleM
   * @return minEleM
   */
  @NotNull 
  @Schema(name = "minEleM", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("minEleM")
  public Integer getMinEleM() {
    return minEleM;
  }

  @JsonProperty("minEleM")
  public void setMinEleM(Integer minEleM) {
    this.minEleM = minEleM;
  }

  public ElevationResponseDto maxEleM(Integer maxEleM) {
    this.maxEleM = maxEleM;
    return this;
  }

  /**
   * Get maxEleM
   * @return maxEleM
   */
  @NotNull 
  @Schema(name = "maxEleM", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("maxEleM")
  public Integer getMaxEleM() {
    return maxEleM;
  }

  @JsonProperty("maxEleM")
  public void setMaxEleM(Integer maxEleM) {
    this.maxEleM = maxEleM;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ElevationResponseDto elevationResponse = (ElevationResponseDto) o;
    return Objects.equals(this.profile, elevationResponse.profile) &&
        Objects.equals(this.gainM, elevationResponse.gainM) &&
        Objects.equals(this.lossM, elevationResponse.lossM) &&
        Objects.equals(this.minEleM, elevationResponse.minEleM) &&
        Objects.equals(this.maxEleM, elevationResponse.maxEleM);
  }

  @Override
  public int hashCode() {
    return Objects.hash(profile, gainM, lossM, minEleM, maxEleM);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ElevationResponseDto {\n");
    sb.append("    profile: ").append(toIndentedString(profile)).append("\n");
    sb.append("    gainM: ").append(toIndentedString(gainM)).append("\n");
    sb.append("    lossM: ").append(toIndentedString(lossM)).append("\n");
    sb.append("    minEleM: ").append(toIndentedString(minEleM)).append("\n");
    sb.append("    maxEleM: ").append(toIndentedString(maxEleM)).append("\n");
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

