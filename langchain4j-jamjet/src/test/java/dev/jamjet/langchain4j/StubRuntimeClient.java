package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.CreateWorkflowResponse;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import dev.jamjet.spring.client.model.StartExecutionResponse;

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
 * {@code exec_<uuid>} (400, as {@code parse_execution_id} does).
 */
class StubRuntimeClient extends JamjetRuntimeClient {

    record RecordedEvent(String executionId, String correlationKey, Map<String, Object> payload) {}

    private final Map<String, Map<String, Object>> workflows = Collections.synchronizedMap(new HashMap<>());
    private final List<ExecutionState> executions = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedEvent> events = Collections.synchronizedList(new ArrayList<>());
    private int workflowCalls;
    private boolean unavailable;

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
        return new StartExecutionResponse("exec_" + uuid.toString().replace("-", ""));
    }

    @Override
    public List<ExecutionState> listExecutions(String status, int limit, int offset) {
        checkUp("/executions");
        synchronized (executions) {
            var all = new ArrayList<>(executions);
            Collections.reverse(all);
            return all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
        }
    }

    @Override
    public void sendExternalEvent(String executionId, String correlationKey, Map<String, Object> payload) {
        checkUp("/executions/" + executionId + "/external-event");
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

    Map<String, Object> registeredIr(String workflowId) {
        return workflows.get(workflowId + "@1.0.0");
    }

    private static JamjetProperties defaultProps() {
        var props = new JamjetProperties();
        props.setRuntimeUrl("http://stub:7700");
        return props;
    }
}
