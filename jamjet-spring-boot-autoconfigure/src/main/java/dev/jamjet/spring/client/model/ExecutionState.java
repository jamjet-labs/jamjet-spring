package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * An execution as {@code GET /executions/{id}} returns it (the runtime's
 * {@code WorkflowExecution}). Status values are lowercase: {@code running},
 * {@code paused}, {@code completed}, {@code failed}, {@code cancelled},
 * {@code limit_exceeded}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionState(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("workflow_version") String workflowVersion,
        @JsonProperty("status") String status,
        @JsonProperty("initial_input") Map<String, Object> initialInput,
        @JsonProperty("current_state") Map<String, Object> currentState,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("completed_at") Instant completedAt
) {

    public boolean isRunning() {
        return "running".equalsIgnoreCase(status);
    }

    public boolean isCompleted() {
        return "completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "failed".equalsIgnoreCase(status);
    }

    public boolean isPaused() {
        return "paused".equalsIgnoreCase(status);
    }
}
