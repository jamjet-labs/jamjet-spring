package dev.jamjet.spring.approval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /jamjet/approvals/{executionId}}. There is no approver
 * field: the approver is the authenticated principal. A {@code user_id} sent by
 * older clients is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewDecision(
        @JsonProperty("decision") String decision,
        @JsonProperty("comment") String comment
) {

    public static ReviewDecision approved(String comment) {
        return new ReviewDecision("approved", comment);
    }

    public static ReviewDecision rejected(String comment) {
        return new ReviewDecision("rejected", comment);
    }
}
