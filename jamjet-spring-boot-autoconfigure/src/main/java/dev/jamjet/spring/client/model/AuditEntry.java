package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * One entry of the runtime's signed, hash-chained audit log ({@code GET /audit}).
 * Field names follow the runtime's {@code AuditLogEntry}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEntry(
        @JsonProperty("id") String id,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("sequence") long sequence,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("actor_type") String actorType,
        @JsonProperty("policy_decision") String policyDecision,
        @JsonProperty("http_method") String httpMethod,
        @JsonProperty("http_path") String httpPath,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("raw_event") Map<String, Object> rawEvent,
        @JsonProperty("entry_hash") String entryHash,
        @JsonProperty("signature") String signature
) {
}
