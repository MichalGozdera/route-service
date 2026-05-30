package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * DemTileResponseDto
 */

@JsonTypeName("DemTileResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class DemTileResponseDto {

  private String name;

  private Integer lonStart;

  private Integer latStart;

  private @Nullable Long remoteSizeBytes = null;

  private Boolean installed;

  private @Nullable Long installedSizeBytes = null;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable Instant installedAt = null;

  private @Nullable Boolean outdated = null;

  public DemTileResponseDto() {
    super();
  }

  public DemTileResponseDto name(String name) {
    this.name = name;
    return this;
  }

  /**
   * e.g. \"N50E020\" (no .tif suffix)
   * @return name
   */
  @NotNull 
  @Schema(name = "name", example = "N50E020", description = "e.g. \"N50E020\" (no .tif suffix)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public DemTileResponseDto lonStart(Integer lonStart) {
    this.lonStart = lonStart;
    return this;
  }

  /**
   * Get lonStart
   * @return lonStart
   */
  @NotNull 
  @Schema(name = "lonStart", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("lonStart")
  public Integer getLonStart() {
    return lonStart;
  }

  @JsonProperty("lonStart")
  public void setLonStart(Integer lonStart) {
    this.lonStart = lonStart;
  }

  public DemTileResponseDto latStart(Integer latStart) {
    this.latStart = latStart;
    return this;
  }

  /**
   * Get latStart
   * @return latStart
   */
  @NotNull 
  @Schema(name = "latStart", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("latStart")
  public Integer getLatStart() {
    return latStart;
  }

  @JsonProperty("latStart")
  public void setLatStart(Integer latStart) {
    this.latStart = latStart;
  }

  public DemTileResponseDto remoteSizeBytes(@Nullable Long remoteSizeBytes) {
    this.remoteSizeBytes = remoteSizeBytes;
    return this;
  }

  /**
   * Get remoteSizeBytes
   * @return remoteSizeBytes
   */
  
  @Schema(name = "remoteSizeBytes", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("remoteSizeBytes")
  public @Nullable Long getRemoteSizeBytes() {
    return remoteSizeBytes;
  }

  @JsonProperty("remoteSizeBytes")
  public void setRemoteSizeBytes(@Nullable Long remoteSizeBytes) {
    this.remoteSizeBytes = remoteSizeBytes;
  }

  public DemTileResponseDto installed(Boolean installed) {
    this.installed = installed;
    return this;
  }

  /**
   * Get installed
   * @return installed
   */
  @NotNull 
  @Schema(name = "installed", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("installed")
  public Boolean getInstalled() {
    return installed;
  }

  @JsonProperty("installed")
  public void setInstalled(Boolean installed) {
    this.installed = installed;
  }

  public DemTileResponseDto installedSizeBytes(@Nullable Long installedSizeBytes) {
    this.installedSizeBytes = installedSizeBytes;
    return this;
  }

  /**
   * Get installedSizeBytes
   * @return installedSizeBytes
   */
  
  @Schema(name = "installedSizeBytes", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("installedSizeBytes")
  public @Nullable Long getInstalledSizeBytes() {
    return installedSizeBytes;
  }

  @JsonProperty("installedSizeBytes")
  public void setInstalledSizeBytes(@Nullable Long installedSizeBytes) {
    this.installedSizeBytes = installedSizeBytes;
  }

  public DemTileResponseDto installedAt(@Nullable Instant installedAt) {
    this.installedAt = installedAt;
    return this;
  }

  /**
   * Get installedAt
   * @return installedAt
   */
  @Valid 
  @Schema(name = "installedAt", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("installedAt")
  public @Nullable Instant getInstalledAt() {
    return installedAt;
  }

  @JsonProperty("installedAt")
  public void setInstalledAt(@Nullable Instant installedAt) {
    this.installedAt = installedAt;
  }

  public DemTileResponseDto outdated(@Nullable Boolean outdated) {
    this.outdated = outdated;
    return this;
  }

  /**
   * Get outdated
   * @return outdated
   */
  
  @Schema(name = "outdated", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("outdated")
  public @Nullable Boolean getOutdated() {
    return outdated;
  }

  @JsonProperty("outdated")
  public void setOutdated(@Nullable Boolean outdated) {
    this.outdated = outdated;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DemTileResponseDto demTileResponse = (DemTileResponseDto) o;
    return Objects.equals(this.name, demTileResponse.name) &&
        Objects.equals(this.lonStart, demTileResponse.lonStart) &&
        Objects.equals(this.latStart, demTileResponse.latStart) &&
        Objects.equals(this.remoteSizeBytes, demTileResponse.remoteSizeBytes) &&
        Objects.equals(this.installed, demTileResponse.installed) &&
        Objects.equals(this.installedSizeBytes, demTileResponse.installedSizeBytes) &&
        Objects.equals(this.installedAt, demTileResponse.installedAt) &&
        Objects.equals(this.outdated, demTileResponse.outdated);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, lonStart, latStart, remoteSizeBytes, installed, installedSizeBytes, installedAt, outdated);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class DemTileResponseDto {\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    lonStart: ").append(toIndentedString(lonStart)).append("\n");
    sb.append("    latStart: ").append(toIndentedString(latStart)).append("\n");
    sb.append("    remoteSizeBytes: ").append(toIndentedString(remoteSizeBytes)).append("\n");
    sb.append("    installed: ").append(toIndentedString(installed)).append("\n");
    sb.append("    installedSizeBytes: ").append(toIndentedString(installedSizeBytes)).append("\n");
    sb.append("    installedAt: ").append(toIndentedString(installedAt)).append("\n");
    sb.append("    outdated: ").append(toIndentedString(outdated)).append("\n");
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

