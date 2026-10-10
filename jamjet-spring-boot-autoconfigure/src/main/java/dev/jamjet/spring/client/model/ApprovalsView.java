package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Response of {@code GET /executions/{id}/approvals}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalsView(
        @JsonProperty("pending") List<PendingApproval> pending,
        @JsonProperty("decided") List<Map<String, Object>> decided
) {

    public ApprovalsView {
        pending = pending == null ? List.of() : List.copyOf(pending);
        decided = decided == null ? List.of() : List.copyOf(decided);
    }
}
