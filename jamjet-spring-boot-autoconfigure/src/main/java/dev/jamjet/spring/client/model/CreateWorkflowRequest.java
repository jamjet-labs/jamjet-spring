package dev.jamjet.spring.client.model;

import java.util.Map;

/**
 * Request to create a workflow from an intermediate representation (IR).
 */
public record CreateWorkflowRequest(Map<String, Object> ir) {
}
