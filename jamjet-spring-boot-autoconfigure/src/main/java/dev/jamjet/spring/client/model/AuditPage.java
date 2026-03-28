package dev.jamjet.spring.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditPage(
        @JsonProperty("items") List<AuditEntry> items,
        @JsonProperty("total") int total,
        @JsonProperty("limit") int limit,
        @JsonProperty("offset") int offset
) {
}
