package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateWorkflowResponse(
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("version") String version
) {
}
