package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A pending approval request from {@code GET /executions/{id}/approvals}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PendingApproval(
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("approver") String approver,
        @JsonProperty("context") Map<String, Object> context,
        @JsonProperty("sequence") Long sequence
) {

    /**
     * The binding a decision must echo: {@code context.action_digest}, or for an
     * agent tool dispatch request that predates action digests,
     * {@code context.calls_hash}. {@code null} when the request binds nothing.
     */
    public String actionDigest() {
        if (context == null) {
            return null;
        }
        Object digest = context.get("action_digest");
        if (digest instanceof String s && !s.isBlank()) {
            return s;
        }
        Object callsHash = context.get("calls_hash");
        if (callsHash instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}
