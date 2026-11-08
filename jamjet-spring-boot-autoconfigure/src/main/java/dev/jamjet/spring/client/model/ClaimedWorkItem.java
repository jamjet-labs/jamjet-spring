package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A work item handed out by {@code POST /work-items/claim}. A worker echoes
 * {@code lease_fence} (and {@code idempotency_key}) when it heartbeats,
 * completes or fails the item, so the runtime can prove the lease is still held.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimedWorkItem(
        @JsonProperty("id") String id,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("queue_type") String queueType,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("attempt") int attempt,
        @JsonProperty("lease_fence") long leaseFence,
        @JsonProperty("lease_secs") Long leaseSecs,
        @JsonProperty("idempotency_key") String idempotencyKey
) {
}
