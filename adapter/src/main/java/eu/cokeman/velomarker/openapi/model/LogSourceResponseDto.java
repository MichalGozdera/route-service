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
 * LogSourceResponseDto
 */

@JsonTypeName("LogSourceResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class LogSourceResponseDto {

  private String name;

  private @Nullable String path;

  private Long sizeBytes;

  private @Nullable Long modifiedAt = null;

  public LogSourceResponseDto() {
    super();
  }

  public LogSourceResponseDto name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Get name
   * @return name
   */
  @NotNull 
  @Schema(name = "name", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public LogSourceResponseDto path(@Nullable String path) {
    this.path = path;
    return this;
  }

  /**
   * Get path
   * @return path
   */
  
  @Schema(name = "path", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("path")
  public @Nullable String getPath() {
    return path;
  }

  @JsonProperty("path")
  public void setPath(@Nullable String path) {
    this.path = path;
  }

  public LogSourceResponseDto sizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
    return this;
  }

  /**
   * Get sizeBytes
   * @return sizeBytes
   */
  @NotNull 
  @Schema(name = "sizeBytes", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("sizeBytes")
  public Long getSizeBytes() {
    return sizeBytes;
  }

  @JsonProperty("sizeBytes")
  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public LogSourceResponseDto modifiedAt(@Nullable Long modifiedAt) {
    this.modifiedAt = modifiedAt;
    return this;
  }

  /**
   * Unix epoch seconds
   * @return modifiedAt
   */
  
  @Schema(name = "modifiedAt", description = "Unix epoch seconds", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("modifiedAt")
  public @Nullable Long getModifiedAt() {
    return modifiedAt;
  }

  @JsonProperty("modifiedAt")
  public void setModifiedAt(@Nullable Long modifiedAt) {
    this.modifiedAt = modifiedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LogSourceResponseDto logSourceResponse = (LogSourceResponseDto) o;
    return Objects.equals(this.name, logSourceResponse.name) &&
        Objects.equals(this.path, logSourceResponse.path) &&
        Objects.equals(this.sizeBytes, logSourceResponse.sizeBytes) &&
        Objects.equals(this.modifiedAt, logSourceResponse.modifiedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, path, sizeBytes, modifiedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class LogSourceResponseDto {\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    path: ").append(toIndentedString(path)).append("\n");
    sb.append("    sizeBytes: ").append(toIndentedString(sizeBytes)).append("\n");
    sb.append("    modifiedAt: ").append(toIndentedString(modifiedAt)).append("\n");
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

