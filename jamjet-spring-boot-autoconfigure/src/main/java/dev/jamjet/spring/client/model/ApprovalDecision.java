package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A decision on a pending approval, as {@code POST /executions/{id}/approve} takes it.
 *
 * <p>{@code actionDigest} is the {@code action_digest} (or, for an agent tool
 * dispatch, the {@code calls_hash}) from the context of the pending approval the
 * reviewer was shown, as listed by {@code GET /executions/{id}/approvals}. Outside
 * dev mode the runtime requires it to approve, and refuses the decision if that
 * request has since been replaced. Pass the value the reviewer actually saw:
 * fetching the current digest and approving in one step approves whatever is
 * pending, unseen.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalDecision(
        @JsonProperty("decision") String decision,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("comment") String comment,
        @JsonProperty("action_digest") String actionDigest
) {

    public static ApprovalDecision approved(String userId, String comment) {
        return new ApprovalDecision("approved", null, userId, comment, null);
    }

    public static ApprovalDecision rejected(String userId, String comment) {
        return new ApprovalDecision("rejected", null, userId, comment, null);
    }

    /** True only for an explicit {@code "approved"} decision. Anything else is a rejection. */
    @JsonIgnore
    public boolean isApproved() {
        return "approved".equals(decision);
    }

    /** A copy bound to one pending runtime request. */
    public ApprovalDecision forRequest(String nodeId, String actionDigest) {
        return new ApprovalDecision(decision, nodeId, userId, comment, actionDigest);
    }
}
