package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionState(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("status") String status,
        @JsonProperty("current_node") String currentNode,
        @JsonProperty("state") Map<String, Object> state,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("error") String error
) {

    public boolean isRunning() {
        return "Running".equalsIgnoreCase(status);
    }

    public boolean isCompleted() {
        return "Completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "Failed".equalsIgnoreCase(status);
    }

    public boolean isPaused() {
        return "Paused".equalsIgnoreCase(status);
    }
}
