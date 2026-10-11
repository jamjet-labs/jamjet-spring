package dev.jamjet.spring.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the workflow IR for a call that runs in the application and is recorded
 * on the runtime: one {@code wait} node released by an external event the
 * application sends when the call ends.
 *
 * <p>The shape follows the runtime's {@code WorkflowIr}: {@code workflow_id} and
 * {@code version} (both required by {@code POST /workflows}), {@code start_node}
 * naming a node in {@code nodes}, {@code nodes} keyed by id with each node's
 * {@code id} and tagged {@code kind}, and {@code edges} as a list. The IR is
 * constant for a given id, so registering it again (every application start)
 * is a no-op on the runtime.
 */
public final class RecordedCallWorkflow {

    /** Version every recorded-call workflow is registered under. */
    public static final String VERSION = "1.0.0";

    private RecordedCallWorkflow() {}

    public static Map<String, Object> ir(String workflowId, String nodeId, String completedEvent,
                                         String name, String source) {
        var kind = new LinkedHashMap<String, Object>();
        kind.put("type", "wait");
        kind.put("condition", "external_event");
        kind.put("correlation_key", completedEvent);

        var node = new LinkedHashMap<String, Object>();
        node.put("id", nodeId);
        node.put("kind", kind);
        node.put("description", name + ", run in the application and reported back with the "
                + completedEvent + " event");

        var ir = new LinkedHashMap<String, Object>();
        ir.put("workflow_id", workflowId);
        ir.put("version", VERSION);
        ir.put("name", name);
        ir.put("state_schema", "");
        ir.put("start_node", nodeId);
        ir.put("nodes", Map.of(nodeId, node));
        ir.put("edges", List.of());
        ir.put("retry_policies", Map.of());
        ir.put("models", Map.of());
        ir.put("tools", Map.of());
        ir.put("mcp_servers", Map.of());
        ir.put("remote_agents", Map.of());
        ir.put("labels", Map.of("source", source));
        return ir;
    }
}
