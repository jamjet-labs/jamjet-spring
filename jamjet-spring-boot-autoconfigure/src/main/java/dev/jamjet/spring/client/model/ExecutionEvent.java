package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * One event from {@code GET /executions/{id}/events}. {@code kind} is the
 * runtime's tagged {@code EventKind}: {@code kind.type} names it (for example
 * {@code workflow_started}, {@code node_completed},
 * {@code external_event_received}) and the other keys are its fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionEvent(
        @JsonProperty("id") String id,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("sequence") long sequence,
        @JsonProperty("kind") Map<String, Object> kind,
        @JsonProperty("created_at") Instant createdAt
) {

    /** The event kind's tag, e.g. {@code external_event_received}. */
    public String type() {
        return kind == null ? null : (String) kind.get("type");
    }

    /** The event's {@code node_id}, for node-scoped kinds; otherwise {@code null}. */
    public String nodeId() {
        Object n = kind == null ? null : kind.get("node_id");
        return n instanceof String s ? s : null;
    }

    /** A field of the event kind, e.g. {@code output} or {@code cost_usd}; {@code null} if absent. */
    public Object field(String name) {
        return kind == null ? null : kind.get(name);
    }

    /** True for an {@code external_event_received} event with this correlation key. */
    public boolean isExternalEvent(String correlationKey) {
        return "external_event_received".equals(type())
                && correlationKey.equals(kind.get("correlation_key"));
    }

    /** The {@code payload} of an external event, or {@code null}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> payload() {
        Object p = kind == null ? null : kind.get("payload");
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
