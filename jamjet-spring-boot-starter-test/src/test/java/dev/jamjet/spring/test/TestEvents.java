package dev.jamjet.spring.test;

import dev.jamjet.spring.client.model.ExecutionEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds events in the runtime's wire shape: {@code kind.type} plus the kind's fields. */
final class TestEvents {

    private static long sequence;

    private TestEvents() {}

    static ExecutionEvent event(String id, String executionId, String type, String nodeId,
                                Map<String, Object> fields, Instant at) {
        var kind = new LinkedHashMap<String, Object>();
        kind.put("type", type);
        if (nodeId != null) {
            kind.put("node_id", nodeId);
        }
        kind.putAll(fields);
        return new ExecutionEvent(id, executionId, ++sequence, kind, at);
    }
}
