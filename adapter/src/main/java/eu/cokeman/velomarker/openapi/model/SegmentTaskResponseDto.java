package eu.cokeman.velomarker.openapi.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * SegmentTaskResponseDto
 */

@JsonTypeName("SegmentTaskResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class SegmentTaskResponseDto {

  private String taskId;

  private String segmentName;

  /**
   * Gets or Sets status
   */
  public enum StatusEnum {
    QUEUED("QUEUED"),
    
    RUNNING("RUNNING"),
    
    COMPLETED("COMPLETED"),
    
    FAILED("FAILED"),
    
    CANCELLED("CANCELLED");

    private final String value;

    StatusEnum(String value) {
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
    public static StatusEnum fromValue(String value) {
      for (StatusEnum b : StatusEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private StatusEnum status;

  public SegmentTaskResponseDto() {
    super();
  }

  public SegmentTaskResponseDto taskId(String taskId) {
    this.taskId = taskId;
    return this;
  }

  /**
   * Get taskId
   * @return taskId
   */
  @NotNull 
  @Schema(name = "taskId", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("taskId")
  public String getTaskId() {
    return taskId;
  }

  @JsonProperty("taskId")
  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public SegmentTaskResponseDto segmentName(String segmentName) {
    this.segmentName = segmentName;
    return this;
  }

  /**
   * Get segmentName
   * @return segmentName
   */
  @NotNull 
  @Schema(name = "segmentName", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("segmentName")
  public String getSegmentName() {
    return segmentName;
  }

  @JsonProperty("segmentName")
  public void setSegmentName(String segmentName) {
    this.segmentName = segmentName;
  }

  public SegmentTaskResponseDto status(StatusEnum status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * @return status
   */
  @NotNull 
  @Schema(name = "status", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("status")
  public StatusEnum getStatus() {
    return status;
  }

  @JsonProperty("status")
  public void setStatus(StatusEnum status) {
    this.status = status;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SegmentTaskResponseDto segmentTaskResponse = (SegmentTaskResponseDto) o;
    return Objects.equals(this.taskId, segmentTaskResponse.taskId) &&
        Objects.equals(this.segmentName, segmentTaskResponse.segmentName) &&
        Objects.equals(this.status, segmentTaskResponse.status);
  }

  @Override
  public int hashCode() {
    return Objects.hash(taskId, segmentName, status);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class SegmentTaskResponseDto {\n");
    sb.append("    taskId: ").append(toIndentedString(taskId)).append("\n");
    sb.append("    segmentName: ").append(toIndentedString(segmentName)).append("\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
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

