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
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}")
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
        assertThat(ir.get("version").asText()).isEqualTo("1.0.0");
        String start = ir.get("start_node").asText();
        assertThat(ir.get("nodes").has(start)).as("start_node names a node").isTrue();
        assertThat(ir.get("nodes").get(start).get("id").asText()).isEqualTo(start);
        assertThat(ir.get("nodes").get(start).get("kind").get("type").asText()).isEqualTo("wait");
        assertThat(ir.get("edges").isArray()).as("edges is a list").isTrue();
        for (String required : List.of("state_schema", "retry_policies", "models", "tools",
                "mcp_servers", "remote_agents")) {
            assertThat(ir.has(required)).as("IR carries %s", required).isTrue();
        }

        var starts = posts("/executions");
        assertThat(starts).hasSize(2);
        JsonNode body = JSON.readTree(starts.get(0).body());
        assertThat(body.get("workflow_id").asText()).isEqualTo("spring-ai-chat");
        assertThat(body.get("workflow_version").asText()).isEqualTo("1.0.0");
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
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
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
    void failsClosedWhenTheCompletionCannotBeRecorded() {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}")
                .on("POST", "/executions/exec_1/external-event", 503, "{\"error\":\"unavailable\"}");
        var chat = chatClient(StubChatModel.answering("hello"));

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(JamjetGovernanceException.class)
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
}
