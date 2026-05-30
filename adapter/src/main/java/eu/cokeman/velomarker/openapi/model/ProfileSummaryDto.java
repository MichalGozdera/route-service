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
 * ProfileSummaryDto
 */

@JsonTypeName("ProfileSummary")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class ProfileSummaryDto {

  private String name;

  private Long sizeBytes;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable Instant modifiedAt = null;

  public ProfileSummaryDto() {
    super();
  }

  public ProfileSummaryDto name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Without .brf suffix
   * @return name
   */
  @NotNull 
  @Schema(name = "name", description = "Without .brf suffix", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public ProfileSummaryDto sizeBytes(Long sizeBytes) {
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

  public ProfileSummaryDto modifiedAt(@Nullable Instant modifiedAt) {
    this.modifiedAt = modifiedAt;
    return this;
  }

  /**
   * Get modifiedAt
   * @return modifiedAt
   */
  @Valid 
  @Schema(name = "modifiedAt", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("modifiedAt")
  public @Nullable Instant getModifiedAt() {
    return modifiedAt;
  }

  @JsonProperty("modifiedAt")
  public void setModifiedAt(@Nullable Instant modifiedAt) {
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
    ProfileSummaryDto profileSummary = (ProfileSummaryDto) o;
    return Objects.equals(this.name, profileSummary.name) &&
        Objects.equals(this.sizeBytes, profileSummary.sizeBytes) &&
        Objects.equals(this.modifiedAt, profileSummary.modifiedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, sizeBytes, modifiedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ProfileSummaryDto {\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
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

