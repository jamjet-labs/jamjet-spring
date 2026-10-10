package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code POST /executions/{id}/approve}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApproveResponse(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("accepted") boolean accepted
) {
}
