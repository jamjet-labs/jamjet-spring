package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEntry(
        @JsonProperty("id") String id,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("timestamp") Instant timestamp
) {
}
