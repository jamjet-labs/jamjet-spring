package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalDecision(
        @JsonProperty("decision") String decision,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("comment") String comment,
        @JsonProperty("state_patch") Map<String, Object> statePatch
) {

    public static ApprovalDecision approved(String userId, String comment) {
        return new ApprovalDecision("approved", null, userId, comment, null);
    }

    public static ApprovalDecision rejected(String userId, String comment) {
        return new ApprovalDecision("rejected", null, userId, comment, null);
    }
}
