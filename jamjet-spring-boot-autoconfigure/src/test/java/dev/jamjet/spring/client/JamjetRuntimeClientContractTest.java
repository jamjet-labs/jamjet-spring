package dev.jamjet.spring.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins every call {@link JamjetRuntimeClient} makes to the route the JamJet
 * runtime actually serves (runtime/api/src/routes.rs): exact method, exact
 * path, exact body. The runtime serves bare routes; it has never served
 * {@code /api/v1}.
 */
class JamjetRuntimeClientContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingRuntime runtime;
    private JamjetRuntimeClient client;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        client = new JamjetRuntimeClient(props);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private static JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    @Test
    void createWorkflowPostsTheIrWrappedUnderIrToBareWorkflows() throws Exception {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"wf\",\"version\":\"1.0.0\"}");

        var resp = client.createWorkflow(Map.of("workflow_id", "wf", "version", "1.0.0"));

        var req = runtime.last();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/workflows");
        assertThat(json(req.body())).isEqualTo(json("{\"ir\":{\"workflow_id\":\"wf\",\"version\":\"1.0.0\"}}"));
        assertThat(resp.workflowId()).isEqualTo("wf");
        assertThat(resp.version()).isEqualTo("1.0.0");
    }

    @Test
    void startExecutionPostsToBareExecutions() throws Exception {
        runtime.on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}");

        var resp = client.startExecution("wf", "1.0.0", Map.of("prompt", "hi"));

        var req = runtime.last();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/executions");
        assertThat(json(req.body())).isEqualTo(json(
                "{\"workflow_id\":\"wf\",\"workflow_version\":\"1.0.0\",\"input\":{\"prompt\":\"hi\"}}"));
        assertThat(resp.executionId()).isEqualTo("exec_1");
    }

    @Test
    void startExecutionOmitsANullVersionSoTheRuntimeDefaultApplies() throws Exception {
        runtime.on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}");

        client.startExecution("wf", null, Map.of());

        assertThat(json(runtime.last().body())).isEqualTo(json("{\"workflow_id\":\"wf\",\"input\":{}}"));
    }

    @Test
    void getExecutionReadsBareExecutionsById() {
        runtime.on("GET", "/executions/exec_1", 200, """
                {"execution_id":"1f0c","workflow_id":"wf","workflow_version":"1.0.0","status":"running",
                 "initial_input":{"prompt":"hi"},"current_state":{"prompt":"hi","k":1},
                 "started_at":"2026-10-10T15:11:52.482512Z","updated_at":"2026-10-10T15:11:52.482512Z",
                 "completed_at":null,"segment_number":0}
                """);

        var state = client.getExecution("exec_1");

        assertThat(runtime.last().method()).isEqualTo("GET");
        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1");
        assertThat(state.workflowId()).isEqualTo("wf");
        assertThat(state.isRunning()).isTrue();
    }

    @Test
    void cancelExecutionPostsToBareCancel() throws Exception {
        runtime.on("POST", "/executions/exec_1/cancel", 200,
                "{\"execution_id\":\"exec_1\",\"status\":\"cancelled\"}");

        client.cancelExecution("exec_1");

        assertThat(runtime.last().method()).isEqualTo("POST");
        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1/cancel");
        assertThat(json(runtime.last().body())).isEqualTo(json("{}"));
    }

    @Test
    void listEventsReadsBareEvents() {
        runtime.on("GET", "/executions/exec_1/events", 200, """
                {"events":[{"id":"e1","execution_id":"1f0c","sequence":1,
                  "kind":{"type":"workflow_started","workflow_id":"wf","workflow_version":"1.0.0","initial_input":{}},
                  "created_at":"2026-10-10T15:11:52.482519Z"}]}
                """);

        var events = client.listEvents("exec_1");

        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1/events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).executionId()).isEqualTo("1f0c");
    }

    @Test
    void queryAuditReadsBareAuditWithFilters() {
        runtime.on("GET", "/audit?limit=50&offset=0&execution_id=exec_1&event_type=approval_received", 200,
                "{\"items\":[],\"total\":0,\"limit\":50,\"offset\":0}");

        var page = client.queryAudit("exec_1", null, "approval_received", 50, 0);

        assertThat(runtime.last().method()).isEqualTo("GET");
        assertThat(runtime.last().path())
                .isEqualTo("/audit?limit=50&offset=0&execution_id=exec_1&event_type=approval_received");
        assertThat(page.total()).isZero();
    }

    @Test
    void sendExternalEventPostsToBareExternalEvent() throws Exception {
        runtime.on("POST", "/executions/exec_1/external-event", 200,
                "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        client.sendExternalEvent("exec_1", "spring_ai.prompt", Map.of("content", "hi"));

        assertThat(runtime.last().method()).isEqualTo("POST");
        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1/external-event");
        assertThat(json(runtime.last().body())).isEqualTo(json(
                "{\"correlation_key\":\"spring_ai.prompt\",\"payload\":{\"content\":\"hi\"}}"));
    }

    @Test
    void approveExecutionPostsToBareApprove() throws Exception {
        runtime.on("POST", "/executions/exec_1/approve", 200,
                "{\"execution_id\":\"exec_1\",\"node_id\":\"n\",\"accepted\":true}");

        var resp = client.approveExecution("exec_1", ApprovalDecision.approved("alice", "ok"));

        assertThat(runtime.last().method()).isEqualTo("POST");
        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1/approve");
        assertThat(json(runtime.last().body())).isEqualTo(json(
                "{\"decision\":\"approved\",\"user_id\":\"alice\",\"comment\":\"ok\"}"));
        assertThat(resp.accepted()).isTrue();
    }

    @Test
    void anHtmlAnswerIsRefusedNotParsed() {
        // The runtime's SPA fallback answers unknown paths with 200 text/html.
        // That must never be mistaken for success, and the message must say why.
        assertThatThrownBy(() -> client.getExecution("exec_unrouted"))
                .isInstanceOf(JamjetClientException.class)
                .hasMessageContaining("Not a JamJet runtime API response")
                .hasMessageContaining("text/html")
                .hasMessageContaining("spring.jamjet.runtime-url");
    }

    @Test
    void getExecutionMapsTheRuntimeFieldNames() {
        runtime.on("GET", "/executions/exec_1", 200, """
                {"execution_id":"1f0c","workflow_id":"wf","workflow_version":"1.0.0","status":"completed",
                 "initial_input":{"prompt":"hi"},"current_state":{"prompt":"hi","k":1},
                 "started_at":"2026-10-10T15:11:52.482512Z","updated_at":"2026-10-10T15:11:53Z",
                 "completed_at":"2026-10-10T15:11:54Z","segment_number":0}
                """);

        var state = client.getExecution("exec_1");

        assertThat(state.executionId()).isEqualTo("1f0c");
        assertThat(state.workflowVersion()).isEqualTo("1.0.0");
        assertThat(state.initialInput()).containsEntry("prompt", "hi");
        assertThat(state.currentState()).containsEntry("k", 1);
        assertThat(state.startedAt()).isEqualTo(Instant.parse("2026-10-10T15:11:52.482512Z"));
        assertThat(state.completedAt()).isEqualTo(Instant.parse("2026-10-10T15:11:54Z"));
        assertThat(state.isCompleted()).isTrue();
    }

    @Test
    void listExecutionsReadsTheExecutionsEnvelope() {
        runtime.on("GET", "/executions?limit=100&offset=200&status=running", 200, """
                {"executions":[{"execution_id":"a","workflow_id":"wf","status":"running","initial_input":{"m":"x"}}]}
                """);

        var list = client.listExecutions("running", 100, 200);

        assertThat(runtime.last().method()).isEqualTo("GET");
        assertThat(runtime.last().path()).isEqualTo("/executions?limit=100&offset=200&status=running");
        assertThat(list).singleElement().satisfies(e -> {
            assertThat(e.executionId()).isEqualTo("a");
            assertThat(e.initialInput()).containsEntry("m", "x");
        });
    }

    @Test
    void listEventsMapsTheTaggedKind() {
        runtime.on("GET", "/executions/exec_1/events", 200, """
                {"events":[{"id":"e9","execution_id":"1f0c","sequence":7,
                  "kind":{"type":"external_event_received","correlation_key":"spring_ai.prompt","payload":{"content":"hi"}},
                  "created_at":"2026-10-10T15:11:52Z"}]}
                """);

        var event = client.listEvents("exec_1").get(0);

        assertThat(event.id()).isEqualTo("e9");
        assertThat(event.sequence()).isEqualTo(7);
        assertThat(event.type()).isEqualTo("external_event_received");
        assertThat(event.isExternalEvent("spring_ai.prompt")).isTrue();
        assertThat(event.isExternalEvent("other")).isFalse();
        assertThat(event.payload()).containsEntry("content", "hi");
        assertThat(event.createdAt()).isEqualTo(Instant.parse("2026-10-10T15:11:52Z"));
    }

    @Test
    void queryAuditMapsTheRuntimeAuditEntry() {
        runtime.on("GET", "/audit?limit=10&offset=0&actor_id=token%3Aabc", 200, """
                {"items":[{"id":"a1","event_id":"e1","execution_id":"1f0c","sequence":4,
                  "event_type":"approval_received","actor_id":"token:abc","actor_type":"human",
                  "http_method":"POST","http_path":"/executions/1f0c/approve",
                  "created_at":"2026-10-10T15:11:52Z","raw_event":{"type":"approval_received"},
                  "entry_hash":"h","signature":"s"}],"total":1,"limit":10,"offset":0}
                """);

        var page = client.queryAudit(null, "token:abc", null, 10, 0);

        assertThat(runtime.last().path()).isEqualTo("/audit?limit=10&offset=0&actor_id=token%3Aabc");
        assertThat(page.total()).isEqualTo(1);
        var entry = page.items().get(0);
        assertThat(entry.eventType()).isEqualTo("approval_received");
        assertThat(entry.httpPath()).isEqualTo("/executions/1f0c/approve");
        assertThat(entry.createdAt()).isEqualTo(Instant.parse("2026-10-10T15:11:52Z"));
        assertThat(entry.rawEvent()).containsEntry("type", "approval_received");
        assertThat(entry.entryHash()).isEqualTo("h");
    }

    @Test
    void listApprovalsReadsBareApprovalsAndExposesTheDigest() {
        runtime.on("GET", "/executions/exec_1/approvals", 200, """
                {"pending":[
                   {"node_id":"pay","tool_name":"send_wire","approver":"human","sequence":5,
                    "context":{"action_digest":"d-123","calls_hash":"c-999"}},
                   {"node_id":"legacy","tool_name":"x","approver":"human","sequence":6,
                    "context":{"calls_hash":"c-777"}},
                   {"node_id":"unbound","tool_name":"y","approver":"human","sequence":7,"context":{}}],
                 "decided":[{"node_id":"old","status":"approved"}]}
                """);

        var view = client.listApprovals("exec_1");

        assertThat(runtime.last().method()).isEqualTo("GET");
        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1/approvals");
        assertThat(view.pending()).extracting(p -> p.actionDigest())
                .containsExactly("d-123", "c-777", null);
        assertThat(view.pending().get(0).nodeId()).isEqualTo("pay");
        assertThat(view.decided()).hasSize(1);
    }

    @Test
    void approveSendsTheNodeAndTheDigestTheReviewerSaw() throws Exception {
        runtime.on("POST", "/executions/exec_1/approve", 200,
                "{\"execution_id\":\"exec_1\",\"node_id\":\"pay\",\"accepted\":true}");

        var decision = new ApprovalDecision("approved", "pay", "alice", "looks right", "d-123");
        var resp = client.approveExecution("exec_1", decision);

        assertThat(json(runtime.last().body())).isEqualTo(json("""
                {"decision":"approved","node_id":"pay","user_id":"alice","comment":"looks right","action_digest":"d-123"}
                """));
        assertThat(resp.nodeId()).isEqualTo("pay");
        assertThat(resp.accepted()).isTrue();
    }

    @Test
    void aRuntimeRefusalCarriesTheRuntimeMessageAndStatus() {
        runtime.on("POST", "/executions/exec_1/approve", 409,
                "{\"error\":\"no pending approval on this execution\"}");

        assertThatThrownBy(() -> client.approveExecution("exec_1", ApprovalDecision.approved("a", null)))
                .isInstanceOfSatisfying(JamjetClientException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getRuntimeMessage()).isEqualTo("no pending approval on this execution");
                    assertThat(e.getMessage()).contains("409").contains("no pending approval on this execution");
                });
    }

    @Test
    void sendsTheBearerTokenAndTenantHeaders() {
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url() + "/");
        props.setApiToken("tok");
        props.setTenantId("acme");
        runtime.on("GET", "/executions/exec_1/events", 200, "{\"events\":[]}");

        new JamjetRuntimeClient(props).listEvents("exec_1");

        assertThat(runtime.last().path()).isEqualTo("/executions/exec_1/events");
        assertThat(runtime.last().header("Authorization")).isEqualTo("Bearer tok");
        assertThat(runtime.last().header("X-Tenant-Id")).isEqualTo("acme");
    }

    @Test
    void noCallEverLeavesTheBareRouteSet() {
        // Belt and braces for the original bug: nothing may be sent under /api.
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"wf\",\"version\":\"1.0.0\"}");
        runtime.on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}");
        runtime.on("GET", "/executions/exec_1", 200, "{\"execution_id\":\"exec_1\"}");
        runtime.on("GET", "/executions/exec_1/events", 200, "{\"events\":[]}");
        runtime.on("GET", "/executions/exec_1/approvals", 200, "{\"pending\":[],\"decided\":[]}");
        runtime.on("POST", "/executions/exec_1/external-event", 200, "{\"accepted\":true}");
        runtime.on("POST", "/executions/exec_1/cancel", 200, "{}");
        runtime.on("GET", "/executions?limit=1&offset=0", 200, "{\"executions\":[]}");
        runtime.on("GET", "/audit?limit=1&offset=0", 200, "{\"items\":[],\"total\":0,\"limit\":1,\"offset\":0}");

        client.createWorkflow(Map.of("workflow_id", "wf", "version", "1.0.0"));
        client.startExecution("wf", null, Map.of());
        client.getExecution("exec_1");
        client.listEvents("exec_1");
        client.listApprovals("exec_1");
        client.sendExternalEvent("exec_1", "k", Map.of());
        client.cancelExecution("exec_1");
        client.listExecutions(null, 1, 0);
        client.queryAudit(null, null, null, 1, 0);

        assertThat(runtime.requests()).hasSize(9)
                .allSatisfy(r -> assertThat(r.path()).doesNotStartWith("/api"));
    }
}
