package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCallWorkflow;
import dev.jamjet.spring.client.model.ApprovalsView;
import dev.jamjet.spring.client.model.ClaimedWorkItem;
import dev.jamjet.spring.client.model.CreateWorkflowResponse;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import dev.jamjet.spring.client.model.StartExecutionResponse;
import dev.jamjet.spring.client.model.WorkItemHeartbeat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An in-memory stand-in for the runtime that refuses what the real one refuses:
 * IR without workflow_id/version/start_node-in-nodes/edges-as-list (400),
 * executions of unregistered workflows (404), and execution ids that are not
 * {@code exec_<uuid>} (400, as {@code parse_execution_id} does). An
 * {@code external_fn} start node puts a work item on its queue, handed out by
 * {@link #claimWorkItem} oldest first and settled only with the claim's fence,
 * as the runtime does.
 */
class StubRuntimeClient extends JamjetRuntimeClient {

    record RecordedEvent(String executionId, String correlationKey, Map<String, Object> payload) {}

    private final Map<String, Map<String, Object>> workflows = Collections.synchronizedMap(new HashMap<>());
    private final List<ExecutionState> executions = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedEvent> events = Collections.synchronizedList(new ArrayList<>());
    private int workflowCalls;
    /** A work item: execution, queue, claimed fence (0 while pending), and how it was settled. */
    static final class Item {
        final String id = UUID.randomUUID().toString();
        final String executionId;
        final String queue;
        long fence;
        String settled;
        Map<String, Object> output;

        Item(String executionId, String queue) {
            this.executionId = executionId;
            this.queue = queue;
        }
    }

    private final List<Item> items = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> statuses = Collections.synchronizedMap(new HashMap<>());
    private long fences;
    private boolean unavailable;
    private boolean eventsFail;
    final java.util.concurrent.atomic.AtomicInteger listCalls = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger listingNow = new java.util.concurrent.atomic.AtomicInteger();
    volatile java.util.concurrent.CountDownLatch listGate;

    /** Simulate a runtime whose store was reset: it forgets every registered workflow. */
    void forgetWorkflows() {
        workflows.clear();
    }

    void setEventsFail(boolean eventsFail) {
        this.eventsFail = eventsFail;
    }

    StubRuntimeClient() {
        super(defaultProps());
    }

    void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    private void checkUp(String path) {
        if (unavailable) {
            throw new JamjetClientException(503, "{\"error\":\"unavailable\"}", path);
        }
    }

    private static UUID parseExecutionId(String id, String path) {
        String hex = id.startsWith("exec_") ? id.substring(5) : id;
        try {
            if (hex.length() == 32) {
                hex = hex.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5");
            }
            return UUID.fromString(hex);
        } catch (IllegalArgumentException e) {
            throw new JamjetClientException(400, "{\"error\":\"invalid execution id: " + id + "\"}", path);
        }
    }

    @Override
    public CreateWorkflowResponse createWorkflow(Map<String, Object> ir) {
        checkUp("/workflows");
        workflowCalls++;
        Object id = ir.get("workflow_id");
        Object version = ir.get("version");
        Object start = ir.get("start_node");
        Object nodes = ir.get("nodes");
        if (!(id instanceof String) || !(version instanceof String)) {
            throw new JamjetClientException(400, "{\"error\":\"ir.workflow_id is required\"}", "/workflows");
        }
        if (!(nodes instanceof Map<?, ?> n) || !(start instanceof String s) || !n.containsKey(s)
                || !(ir.get("edges") instanceof List<?>)) {
            throw new JamjetClientException(400, "{\"error\":\"invalid workflow IR\"}", "/workflows");
        }
        workflows.put(id + "@" + version, ir);
        return new CreateWorkflowResponse((String) id, (String) version);
    }

    @Override
    public StartExecutionResponse startExecution(String workflowId, String version, Map<String, Object> input) {
        checkUp("/executions");
        String v = version == null ? "1.0.0" : version;
        if (!workflows.containsKey(workflowId + "@" + v)) {
            throw new JamjetClientException(404, "{\"error\":\"workflow " + workflowId + " v" + v + "\"}",
                    "/executions");
        }
        var uuid = UUID.randomUUID();
        executions.add(new ExecutionState(uuid.toString(), workflowId, v, "running",
                new LinkedHashMap<>(input), new LinkedHashMap<>(input), Instant.now(), Instant.now(), null));
        String executionId = "exec_" + uuid.toString().replace("-", "");
        statuses.put(executionId, "running");
        var ir = workflows.get(workflowId + "@" + v);
        var node = (Map<?, ?>) ((Map<?, ?>) ir.get("nodes")).get(ir.get("start_node"));
        if (node.get("kind") instanceof Map<?, ?> kind && "external_fn".equals(kind.get("type"))) {
            items.add(new Item(executionId, (String) kind.get("queue")));
        }
        return new StartExecutionResponse(executionId);
    }

    @Override
    public synchronized ClaimedWorkItem claimWorkItem(String workerId, List<String> queueTypes,
                                                      java.time.Duration lease) {
        checkUp("/work-items/claim");
        synchronized (items) {
            for (Item item : items) {
                if (item.fence == 0 && item.settled == null && queueTypes.contains(item.queue)) {
                    item.fence = ++fences;
                    return new ClaimedWorkItem(item.id, item.executionId, "node", item.queue, Map.of(), 0,
                            item.fence, lease.toSeconds(), "key-" + item.id);
                }
            }
        }
        return null;
    }

    private Item held(String id, long fence, String path) {
        synchronized (items) {
            for (Item item : items) {
                if (item.id.equals(id)) {
                    if (item.fence != fence || item.settled != null) {
                        throw new JamjetClientException(409, "{\"reason\":\"stale or invalid lease fence\"}", path);
                    }
                    return item;
                }
            }
        }
        throw new JamjetClientException(404, "{\"error\":\"work item\"}", path);
    }

    @Override
    public void completeWorkItem(String workItemId, Map<String, Object> output, long durationMs, long leaseFence,
                                 String idempotencyKey) {
        checkUp("/work-items/" + workItemId + "/complete");
        Item item = held(workItemId, leaseFence, "/work-items/" + workItemId + "/complete");
        item.settled = "completed";
        item.output = output;
        statuses.put(item.executionId, "completed");
    }

    @Override
    public void failWorkItem(String workItemId, String error, long leaseFence) {
        checkUp("/work-items/" + workItemId + "/fail");
        Item item = held(workItemId, leaseFence, "/work-items/" + workItemId + "/fail");
        item.settled = "failed";
        item.output = Map.of("error", error);
        statuses.put(item.executionId, "failed");
    }

    @Override
    public WorkItemHeartbeat heartbeatWorkItem(String workItemId, String workerId, long leaseFence) {
        checkUp("/work-items/" + workItemId + "/heartbeat");
        Item item = held(workItemId, leaseFence, "/work-items/" + workItemId + "/heartbeat");
        return new WorkItemHeartbeat(true, item.fence, false, false);
    }

    @Override
    public ExecutionState getExecution(String executionId) {
        checkUp("/executions/" + executionId);
        String status = statuses.get(executionId);
        if (status == null) {
            throw new JamjetClientException(404, "{\"error\":\"execution " + executionId + "\"}",
                    "/executions/" + executionId);
        }
        return new ExecutionState(executionId, null, null, status, Map.of(), Map.of(), Instant.now(),
                Instant.now(), null);
    }

    @Override
    public ApprovalsView listApprovals(String executionId) {
        checkUp("/executions/" + executionId + "/approvals");
        return new ApprovalsView(List.of(), List.of());
    }

    /** The status the stub holds for an execution: running until its item is completed or failed. */
    String status(String executionId) {
        return statuses.get(executionId);
    }

    List<Item> items() {
        return List.copyOf(items);
    }

    @Override
    public List<ExecutionState> listExecutions(String status, int limit, int offset) {
        checkUp("/executions");
        listCalls.incrementAndGet();
        listingNow.incrementAndGet();
        try {
            var gate = listGate;
            if (gate != null) {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            listingNow.decrementAndGet();
        }
        synchronized (executions) {
            var all = new ArrayList<>(executions);
            Collections.reverse(all);
            return all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
        }
    }

    @Override
    public void sendExternalEvent(String executionId, String correlationKey, Map<String, Object> payload) {
        checkUp("/executions/" + executionId + "/external-event");
        if (eventsFail) {
            throw new JamjetClientException(503, "{\"error\":\"event store unavailable\"}",
                    "/executions/" + executionId + "/external-event");
        }
        parseExecutionId(executionId, "/executions/" + executionId + "/external-event");
        events.add(new RecordedEvent(executionId, correlationKey, payload));
    }

    @Override
    public List<ExecutionEvent> listEvents(String executionId) {
        checkUp("/executions/" + executionId + "/events");
        UUID wanted = parseExecutionId(executionId, "/executions/" + executionId + "/events");
        var out = new ArrayList<ExecutionEvent>();
        long seq = 0;
        synchronized (events) {
            for (var e : events) {
                if (parseExecutionId(e.executionId(), "").equals(wanted)) {
                    var kind = new LinkedHashMap<String, Object>();
                    kind.put("type", "external_event_received");
                    kind.put("correlation_key", e.correlationKey());
                    kind.put("payload", e.payload());
                    out.add(new ExecutionEvent(UUID.randomUUID().toString(), wanted.toString(), ++seq, kind,
                            Instant.now()));
                }
            }
        }
        return out;
    }

    @Override
    public boolean isHealthy() {
        return !unavailable;
    }

    List<RecordedEvent> getEvents() {
        return List.copyOf(events);
    }

    int getWorkflowCalls() {
        return workflowCalls;
    }

    int getExecutionCount() {
        return executions.size();
    }

    List<ExecutionState> executions() {
        return List.copyOf(executions);
    }

    Map<String, Object> registeredIr(String workflowId) {
        return workflows.get(workflowId + "@" + RecordedCallWorkflow.VERSION);
    }

    private static JamjetProperties defaultProps() {
        var props = new JamjetProperties();
        props.setRuntimeUrl("http://stub:7700");
        return props;
    }
}
