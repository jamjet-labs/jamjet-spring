package dev.jamjet.spring.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.RuntimeErrorMode;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JamjetDurabilityAdvisorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingRuntime runtime;
    private JamjetProperties props;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private void runtimeAccepts() {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"2.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}").acceptsWorkItems("exec_1")
                .on("POST", "/executions/exec_1/external-event", 200,
                        "{\"execution_id\":\"exec_1\",\"accepted\":true}");
    }

    private ChatClient chatClient(StubChatModel model) {
        var advisor = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(props), props);
        return ChatClient.builder(model).defaultAdvisors(advisor).build();
    }

    private List<RecordingRuntime.Request> posts(String path) {
        var out = new ArrayList<RecordingRuntime.Request>();
        for (var r : runtime.requests()) {
            if (r.method().equals("POST") && r.path().equals(path)) {
                out.add(r);
            }
        }
        return out;
    }

    @Test
    void registersOneValidWorkflowAndStartsAnExecutionPerCall() throws Exception {
        runtimeAccepts();
        var model = StubChatModel.answering("hello");
        var chat = chatClient(model);

        assertThat(chat.prompt("hi").call().content()).isEqualTo("hello");
        assertThat(chat.prompt("again").call().content()).isEqualTo("hello");

        // One registration: the IR is constant, so the runtime can treat a
        // re-registration after a restart as a no-op.
        var workflows = posts("/workflows");
        assertThat(workflows).hasSize(1);
        JsonNode ir = JSON.readTree(workflows.get(0).body()).get("ir");
        assertThat(ir.get("workflow_id").asText()).isEqualTo("spring-ai-chat");
        assertThat(ir.get("version").asText()).isEqualTo("2.0.0");
        String start = ir.get("start_node").asText();
        assertThat(ir.get("nodes").has(start)).as("start_node names a node").isTrue();
        assertThat(ir.get("nodes").get(start).get("id").asText()).isEqualTo(start);
        // A kind the runtime runs: an external_fn node on the application's own queue.
        JsonNode kind = ir.get("nodes").get(start).get("kind");
        assertThat(kind.get("type").asText()).isEqualTo("external_fn");
        assertThat(kind.get("queue").asText()).isEqualTo("spring-ai-chat.calls");
        assertThat(kind.get("handler").asText()).isEqualTo("record_call");
        assertThat(ir.get("nodes").get(start).get("retry_policy").asText()).isEqualTo("no_retry");
        assertThat(ir.get("edges").isArray()).as("edges is a list").isTrue();
        for (String required : List.of("state_schema", "retry_policies", "models", "tools",
                "mcp_servers", "remote_agents")) {
            assertThat(ir.has(required)).as("IR carries %s", required).isTrue();
        }

        var starts = posts("/executions");
        assertThat(starts).hasSize(2);
        JsonNode body = JSON.readTree(starts.get(0).body());
        assertThat(body.get("workflow_id").asText()).isEqualTo("spring-ai-chat");
        assertThat(body.get("workflow_version").asText()).isEqualTo("2.0.0");
        assertThat(body.get("input").get("prompt").asText()).isEqualTo("hi");
        assertThat(body.get("input").hasNonNull("session_id")).isTrue();

        var completions = posts("/executions/exec_1/external-event");
        assertThat(completions).hasSize(2);
        JsonNode done = JSON.readTree(completions.get(0).body());
        assertThat(done.get("correlation_key").asText()).isEqualTo("spring_ai.completed");
        assertThat(done.get("payload").get("status").asText()).isEqualTo("completed");
    }

    @Test
    void failsClosedByDefaultWhenTheRuntimeCannotStartTheExecution() {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"2.0.0\"}")
                .on("POST", "/executions", 500, "{\"error\":\"backend error: disk full\"}");
        var model = StubChatModel.answering("hello");
        var chat = chatClient(model);

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("backend error: disk full")
                .hasMessageContaining("spring.jamjet.on-runtime-error");
        assertThat(model.calls).as("the model is never called ungoverned").hasValue(0);
    }

    @Test
    void failsClosedWhenTheUrlIsNotTheRuntimeApi() {
        // No routes: every answer is the SPA's 200 text/html, as with the old /api/v1 paths.
        var model = StubChatModel.answering("hello");
        var chat = chatClient(model);

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("text/html");
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void failOpenRunsTheCallWhenTheRuntimeIsDown() {
        props.setOnRuntimeError(RuntimeErrorMode.FAIL_OPEN);
        var model = StubChatModel.answering("hello");
        var chat = chatClient(model);

        assertThat(chat.prompt("hi").call().content()).isEqualTo("hello");
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void failsClosedAsARecordingFailureWhenTheCompletionCannotBeRecorded() {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"2.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}").acceptsWorkItems("exec_1")
                .on("POST", "/executions/exec_1/external-event", 503, "{\"error\":\"unavailable\"}");
        var chat = chatClient(StubChatModel.answering("hello"));

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(dev.jamjet.spring.JamjetRecordingException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void recordsAFailedOutcomeWhenTheModelThrows() throws Exception {
        runtimeAccepts();
        var chat = chatClient(StubChatModel.failingWith(new IllegalStateException("model exploded")));

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model exploded");

        var events = posts("/executions/exec_1/external-event");
        assertThat(events).hasSize(1);
        JsonNode body = JSON.readTree(events.get(0).body());
        assertThat(body.get("correlation_key").asText()).isEqualTo("spring_ai.completed");
        assertThat(body.get("payload").get("status").asText()).isEqualTo("failed");
        assertThat(body.get("payload").get("error").asText()).contains("model exploded");
    }

    @Test
    void doesNothingWhenDurabilityIsDisabled() {
        props.setDurabilityEnabled(false);
        var chat = chatClient(StubChatModel.answering("hello"));

        assertThat(chat.prompt("hi").call().content()).isEqualTo("hello");
        assertThat(runtime.requests()).isEmpty();
    }

    @Test
    void aRuntimeThatLostTheWorkflowGetsItRegisteredAgainOnce() {
        runtimeAccepts();
        var chat = chatClient(StubChatModel.answering("hello"));
        chat.prompt("first").call().content();

        // The runtime's store was reset: it no longer knows the workflow until it is registered again.
        runtime.on("POST", "/executions", 404, "{\"error\":\"workflow spring-ai-chat v2.0.0\"}");
        runtime.whenReceived("POST", "/workflows",
                () -> runtime.on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}").acceptsWorkItems("exec_1"));

        assertThat(chat.prompt("second").call().content()).isEqualTo("hello");
        assertThat(runtime.count("POST", "/workflows")).isEqualTo(2);
        assertThat(runtime.count("POST", "/executions")).isEqualTo(3);
    }

    @Test
    void aWorkflowStillMissingAfterReRegistrationFailsClosed() {
        runtimeAccepts();
        var model = StubChatModel.answering("hello");
        var chat = chatClient(model);
        chat.prompt("first").call().content();
        runtime.on("POST", "/executions", 404, "{\"error\":\"workflow spring-ai-chat v2.0.0\"}");

        assertThatThrownBy(() -> chat.prompt("second").call().content())
                .isInstanceOf(JamjetGovernanceException.class);
        assertThat(runtime.count("POST", "/workflows")).isEqualTo(2);
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void theCallHoldsItsNodeAndCompletesItWithTheOutcome() throws Exception {
        runtimeAccepts();
        runtime.on("POST", "/work-items/claim", 200, """
                        {"claimed":true,"work_item":{"id":"wi-1","execution_id":"exec_1","node_id":"chat",
                        "queue_type":"spring-ai-chat.calls","payload":{},"attempt":0,"lease_fence":5,"lease_secs":60,
                        "idempotency_key":"k"}}""")
                .on("POST", "/work-items/wi-1/complete", 200, "{\"completed\":true}");

        assertThat(chatClient(StubChatModel.answering("hello")).prompt("hi").call().content()).isEqualTo("hello");

        // The outcome event first, then the node completed with the same outcome.
        var paths = runtime.requests().stream().map(RecordingRuntime.Request::path).toList();
        assertThat(paths).containsSubsequence("/executions", "/work-items/claim",
                "/executions/exec_1/external-event", "/work-items/wi-1/complete");
        JsonNode complete = JSON.readTree(posts("/work-items/wi-1/complete").get(0).body());
        assertThat(complete.get("output").get("status").asText()).isEqualTo("completed");
        assertThat(complete.get("lease_fence").asLong()).isEqualTo(5);
    }

    @Test
    void aCallTheRuntimeHoldsForApprovalIsRefusedEvenWhenFailOpen() {
        props.setOnRuntimeError(RuntimeErrorMode.FAIL_OPEN);
        runtimeAccepts();
        // The claim gate held the node: nothing is handed out, and the request is in the log.
        runtime.on("POST", "/work-items/claim", 200, "{\"claimed\":false}")
                .on("GET", "/executions/exec_1/events", 200, """
                        {"events":[{"id":"e1","execution_id":"exec_1","sequence":3,"kind":{"type":"tool_approval_required",
                        "node_id":"chat","tool_name":"spring-ai-chat.calls/record_call","approver":"human","context":{}}}]}""");
        var model = StubChatModel.answering("hello");

        assertThatThrownBy(() -> chatClient(model).prompt("hi").call().content())
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("for approval");
        assertThat(model.calls).as("the model is never called").hasValue(0);
        assertThat(posts("/executions/exec_1/cancel")).hasSize(1);
    }

    @Test
    void aClaimTheRuntimeCannotAnswerRefusesTheCallInFailClosedMode() {
        runtimeAccepts();
        runtime.on("POST", "/work-items/claim", 503, "{\"error\":\"backend unavailable\"}");
        var model = StubChatModel.answering("hello");

        assertThatThrownBy(() -> chatClient(model).prompt("hi").call().content())
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("backend unavailable");
        assertThat(model.calls).hasValue(0);
        assertThat(posts("/executions/exec_1/cancel")).as("the started execution is cancelled").hasSize(1);
    }
}
