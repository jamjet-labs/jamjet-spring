package dev.jamjet.spring.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the workflow IR for a call that runs in the application and is recorded
 * on the runtime.
 *
 * <p><b>{@value #VERSION}</b> (the current shape): one {@code external_fn} node on
 * a queue of the application's own ({@link #queueFor}). Starting an execution puts
 * the node's work item on that queue; the application claims it, holds the lease
 * while the call runs and completes it (or fails it) with the call's outcome when
 * the call ends, over the runtime's work-item protocol. The execution is
 * {@code running} for exactly as long as the call is, and the node retries
 * nothing ({@code no_retry}): if the application stops mid-call, its lease lapses
 * and the runtime fails the execution instead of leaving it open.
 *
 * <p><b>{@value #LEGACY_VERSION}</b>: one {@code wait} node released by an
 * external event. Registered only on a runtime that predates {@code external_fn}
 * nodes (JamJet 0.5.x), which completes that node as soon as the execution starts.
 * A runtime that refuses node kinds nothing executes (jamjet PR #169 and later)
 * refuses it, so it is never sent to one. Kept byte for byte so a runtime that
 * already holds it re-registers it as a no-op.
 *
 * <p>The shape follows the runtime's {@code WorkflowIr}: {@code workflow_id} and
 * {@code version} (both required by {@code POST /workflows}), {@code start_node}
 * naming a node in {@code nodes}, {@code nodes} keyed by id with each node's
 * {@code id} and tagged {@code kind}, and {@code edges} as a list. The IR is
 * constant for a given id, so registering it again (every application start)
 * is a no-op on the runtime.
 */
public final class RecordedCallWorkflow {

    /** Version the {@code external_fn} shape is registered under. */
    public static final String VERSION = "2.0.0";

    /** Version the {@code wait} shape is registered under, on runtimes without {@code external_fn}. */
    public static final String LEGACY_VERSION = "1.0.0";

    /** Handler named by the node of a recorded call. */
    public static final String CALL_HANDLER = "record_call";

    /** The longest queue name the runtime accepts. */
    static final int MAX_QUEUE_LENGTH = 64;

    private RecordedCallWorkflow() {}

    /**
     * The {@value #VERSION} IR: one {@code external_fn} node on {@code queue},
     * naming {@code handler}, that the application claims and settles itself.
     */
    public static Map<String, Object> ir(String workflowId, String nodeId, String name, String source,
                                         String queue, String handler, String description) {
        var kind = new LinkedHashMap<String, Object>();
        kind.put("type", "external_fn");
        kind.put("queue", queue);
        kind.put("handler", handler);
        kind.put("output_schema", "");

        var node = new LinkedHashMap<String, Object>();
        node.put("id", nodeId);
        node.put("kind", kind);
        node.put("retry_policy", "no_retry");
        node.put("description", description);

        return workflow(workflowId, VERSION, nodeId, node, name, source);
    }

    /**
     * The {@value #VERSION} IR of a recorded call: queue {@link #queueFor}, handler
     * {@value #CALL_HANDLER}, completed with the outcome the application also
     * reports as the {@code completedEvent} external event.
     */
    public static Map<String, Object> ir(String workflowId, String nodeId, String completedEvent,
                                         String name, String source) {
        String queue = queueFor(workflowId);
        return ir(workflowId, nodeId, name, source, queue, CALL_HANDLER, callDescription(name, queue, completedEvent));
    }

    /** The node description of a recorded call. */
    static String callDescription(String name, String queue, String completedEvent) {
        return name + ", run in the application, which claims this node from queue " + queue
                + " and completes it with the outcome it reports as the " + completedEvent + " event";
    }

    /** The {@value #LEGACY_VERSION} IR, for runtimes that predate {@code external_fn} nodes. */
    public static Map<String, Object> legacyIr(String workflowId, String nodeId, String completedEvent,
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

        return workflow(workflowId, LEGACY_VERSION, nodeId, node, name, source);
    }

    /**
     * The queue a workflow's recorded calls go to: {@code <workflowId>.calls}, or,
     * when the workflow id has characters a queue name cannot hold or is too long,
     * {@code recorded-calls.} and the first 32 hex characters of the id's SHA-256. The runtime accepts at most
     * {@value #MAX_QUEUE_LENGTH} ASCII letters, digits, {@code -}, {@code _} and
     * {@code .}, and no built-in queue name; a {@code .calls} suffix is never one.
     */
    public static String queueFor(String workflowId) {
        return queueFor(workflowId, "calls");
    }

    /** {@link #queueFor(String)} with a suffix other than {@code calls}. */
    public static String queueFor(String workflowId, String suffix) {
        String queue = workflowId + "." + suffix;
        if (queue.length() <= MAX_QUEUE_LENGTH && queue.chars().allMatch(RecordedCallWorkflow::queueChar)) {
            return queue;
        }
        return "recorded-" + suffix + "." + sha256(workflowId).substring(0, 32);
    }

    private static boolean queueChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
    }

    private static Map<String, Object> workflow(String workflowId, String version, String nodeId,
                                                Map<String, Object> node, String name, String source) {
        var ir = new LinkedHashMap<String, Object>();
        ir.put("workflow_id", workflowId);
        ir.put("version", version);
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
