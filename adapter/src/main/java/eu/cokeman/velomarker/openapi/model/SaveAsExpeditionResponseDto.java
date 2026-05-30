package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
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
 * SaveAsExpeditionResponseDto
 */

@JsonTypeName("SaveAsExpeditionResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class SaveAsExpeditionResponseDto {

  private UUID groupId;

  @Valid
  private List<UUID> draftIds = new ArrayList<>();

  public SaveAsExpeditionResponseDto() {
    super();
  }

  public SaveAsExpeditionResponseDto groupId(UUID groupId) {
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

  public SaveAsExpeditionResponseDto draftIds(List<UUID> draftIds) {
    this.draftIds = draftIds;
    return this;
  }

  public SaveAsExpeditionResponseDto addDraftIdsItem(UUID draftIdsItem) {
    if (this.draftIds == null) {
      this.draftIds = new ArrayList<>();
    }
    this.draftIds.add(draftIdsItem);
    return this;
  }

  /**
   * Get draftIds
   * @return draftIds
   */
  @NotNull @Valid 
  @Schema(name = "draftIds", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("draftIds")
  public List<UUID> getDraftIds() {
    return draftIds;
  }

  @JsonProperty("draftIds")
  public void setDraftIds(List<UUID> draftIds) {
    this.draftIds = draftIds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SaveAsExpeditionResponseDto saveAsExpeditionResponse = (SaveAsExpeditionResponseDto) o;
    return Objects.equals(this.groupId, saveAsExpeditionResponse.groupId) &&
        Objects.equals(this.draftIds, saveAsExpeditionResponse.draftIds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, draftIds);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class SaveAsExpeditionResponseDto {\n");
    sb.append("    groupId: ").append(toIndentedString(groupId)).append("\n");
    sb.append("    draftIds: ").append(toIndentedString(draftIds)).append("\n");
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

