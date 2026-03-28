package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record StartExecutionRequest(
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("workflow_version") String workflowVersion,
        @JsonProperty("input") Map<String, Object> input
) {
}
