package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Answer of {@code POST /work-items/{id}/heartbeat}. {@code cancelRequested} is
 * true when the item's execution has reached a terminal state (it was cancelled,
 * for example), so the worker should stop working on it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkItemHeartbeat(
        @JsonProperty("renewed") boolean renewed,
        @JsonProperty("lease_fence") long leaseFence,
        @JsonProperty("reattached") boolean reattached,
        @JsonProperty("cancel_requested") boolean cancelRequested
) {
}
