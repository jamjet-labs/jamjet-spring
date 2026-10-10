package dev.jamjet.spring.approval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /jamjet/approvals/{executionId}}. {@code content_digest}
 * is the digest {@code /pending} showed for the call; a decision without it is
 * refused (400), one with a different digest is stale (409). There is no approver
 * field: the approver is the authenticated principal. A {@code user_id} sent by
 * older clients is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewDecision(
        @JsonProperty("decision") String decision,
        @JsonProperty("comment") String comment,
        @JsonProperty("content_digest") String contentDigest
) {

    public static ReviewDecision approved(String contentDigest, String comment) {
        return new ReviewDecision("approved", comment, contentDigest);
    }

    public static ReviewDecision rejected(String contentDigest, String comment) {
        return new ReviewDecision("rejected", comment, contentDigest);
    }
}
