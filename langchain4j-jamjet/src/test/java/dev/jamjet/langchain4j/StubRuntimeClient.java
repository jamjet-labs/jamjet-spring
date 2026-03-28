package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.AuditEntry;
import dev.jamjet.spring.client.model.AuditPage;
import dev.jamjet.spring.client.model.CreateWorkflowResponse;
import dev.jamjet.spring.client.model.StartExecutionResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class StubRuntimeClient extends JamjetRuntimeClient {

    private final List<RecordedEvent> events = Collections.synchronizedList(new ArrayList<>());
    private int workflowCounter = 0;
    private int executionCounter = 0;
    private boolean unavailable = false;

    StubRuntimeClient() {
        super(defaultProps());
    }

    void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    @Override
    public CreateWorkflowResponse createWorkflow(Map<String, Object> ir) {
        if (unavailable) throw new JamjetClientException(503, "unavailable", "/api/v1/workflows");
        workflowCounter++;
        return new CreateWorkflowResponse("wf-" + workflowCounter, "1");
    }

    @Override
    public StartExecutionResponse startExecution(String workflowId, String version, Map<String, Object> input) {
        if (unavailable) throw new JamjetClientException(503, "unavailable", "/api/v1/executions");
        executionCounter++;
        return new StartExecutionResponse("exec-" + executionCounter);
    }

    @Override
    public void sendExternalEvent(String executionId, String correlationKey, Map<String, Object> payload) {
        if (unavailable) throw new JamjetClientException(503, "unavailable", "/external-event");
        events.add(new RecordedEvent(executionId, correlationKey, payload));
    }

    @Override
    public AuditPage queryAudit(String executionId, String actorId, String eventType, int limit, int offset) {
        if (unavailable) throw new JamjetClientException(503, "unavailable", "/api/v1/audit");
        var matching = events.stream()
                .filter(e -> executionId == null || executionId.equals(e.executionId))
                .filter(e -> eventType == null || eventType.equals(e.correlationKey))
                .map(e -> new AuditEntry(
                        "audit-" + events.indexOf(e),
                        e.executionId,
                        e.correlationKey,
                        null,
                        null,
                        e.payload,
                        Instant.now()))
                .toList();
        return new AuditPage(matching, matching.size(), limit, offset);
    }

    @Override
    public boolean isHealthy() {
        return !unavailable;
    }

    List<RecordedEvent> getEvents() {
        return List.copyOf(events);
    }

    int getWorkflowCount() { return workflowCounter; }
    int getExecutionCount() { return executionCounter; }

    record RecordedEvent(String executionId, String correlationKey, Map<String, Object> payload) {}

    private static JamjetProperties defaultProps() {
        var props = new JamjetProperties();
        props.setRuntimeUrl("http://stub:7700");
        return props;
    }
}
