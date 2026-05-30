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
 * WorkerStatusResponseDto
 */

@JsonTypeName("WorkerStatusResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.22.0")
public class WorkerStatusResponseDto {

  private String name;

  private String state;

  private @Nullable Integer pid = null;

  private @Nullable String uptime = null;

  public WorkerStatusResponseDto() {
    super();
  }

  public WorkerStatusResponseDto name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Get name
   * @return name
   */
  @NotNull 
  @Schema(name = "name", example = "worker-0", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public WorkerStatusResponseDto state(String state) {
    this.state = state;
    return this;
  }

  /**
   * Get state
   * @return state
   */
  @NotNull 
  @Schema(name = "state", example = "RUNNING", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("state")
  public String getState() {
    return state;
  }

  @JsonProperty("state")
  public void setState(String state) {
    this.state = state;
  }

  public WorkerStatusResponseDto pid(@Nullable Integer pid) {
    this.pid = pid;
    return this;
  }

  /**
   * Get pid
   * @return pid
   */
  
  @Schema(name = "pid", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("pid")
  public @Nullable Integer getPid() {
    return pid;
  }

  @JsonProperty("pid")
  public void setPid(@Nullable Integer pid) {
    this.pid = pid;
  }

  public WorkerStatusResponseDto uptime(@Nullable String uptime) {
    this.uptime = uptime;
    return this;
  }

  /**
   * Free-form supervisorctl uptime string
   * @return uptime
   */
  
  @Schema(name = "uptime", description = "Free-form supervisorctl uptime string", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("uptime")
  public @Nullable String getUptime() {
    return uptime;
  }

  @JsonProperty("uptime")
  public void setUptime(@Nullable String uptime) {
    this.uptime = uptime;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    WorkerStatusResponseDto workerStatusResponse = (WorkerStatusResponseDto) o;
    return Objects.equals(this.name, workerStatusResponse.name) &&
        Objects.equals(this.state, workerStatusResponse.state) &&
        Objects.equals(this.pid, workerStatusResponse.pid) &&
        Objects.equals(this.uptime, workerStatusResponse.uptime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, state, pid, uptime);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class WorkerStatusResponseDto {\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    state: ").append(toIndentedString(state)).append("\n");
    sb.append("    pid: ").append(toIndentedString(pid)).append("\n");
    sb.append("    uptime: ").append(toIndentedString(uptime)).append("\n");
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

