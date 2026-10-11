package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Body of {@code POST /executions}. A null {@code workflowVersion} is omitted so
 * the runtime applies its default ({@code "1.0.0"}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartExecutionRequest(
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("workflow_version") String workflowVersion,
        @JsonProperty("input") Map<String, Object> input
) {
}
