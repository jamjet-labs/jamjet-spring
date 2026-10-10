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

class JamjetAuditAdvisorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENTS = "/executions/exec_1/external-event";

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

    private ChatClient chatClient(StubChatModel model) {
        var advisor = new JamjetAuditAdvisor(new JamjetRuntimeClient(props), props);
        return ChatClient.builder(model).defaultAdvisors(advisor).build();
    }

    private String call(ChatClient chat, String prompt) {
        return chat.prompt(prompt)
                .advisors(a -> a.param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content();
    }

    private List<JsonNode> recorded() throws Exception {
        var out = new ArrayList<JsonNode>();
        for (var r : runtime.requests()) {
            if (r.method().equals("POST") && r.path().equals(EVENTS)) {
                out.add(JSON.readTree(r.body()));
            }
        }
        return out;
    }

    @Test
    void recordsThePromptAndTheResponseOnTheExecution() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        assertThat(call(chatClient(StubChatModel.answering("hello")), "hi")).isEqualTo("hello");

        var events = recorded();
        assertThat(events).extracting(e -> e.get("correlation_key").asText())
                .containsExactly("spring_ai.prompt", "spring_ai.response");
        assertThat(events.get(0).get("payload").get("content").asText()).isEqualTo("hi");
        assertThat(events.get(1).get("payload").get("content").asText()).isEqualTo("hello");
    }

    @Test
    void leavesContentOutWhenConfiguredTo() throws Exception {
        props.getAudit().setIncludePrompts(false);
        props.getAudit().setIncludeResponses(false);
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        call(chatClient(StubChatModel.answering("hello")), "secret");

        assertThat(recorded()).hasSize(2).allSatisfy(e -> assertThat(e.get("payload").has("content")).isFalse());
    }

    @Test
    void failsClosedByDefaultWhenThePromptCannotBeRecorded() {
        runtime.on("POST", EVENTS, 500, "{\"error\":\"backend error: locked\"}");
        var model = StubChatModel.answering("hello");

        assertThatThrownBy(() -> call(chatClient(model), "hi"))
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("backend error: locked");
        assertThat(model.calls).as("an unaudited prompt never reaches the model").hasValue(0);
    }

    @Test
    void failOpenLetsTheCallRunUnaudited() {
        props.setOnRuntimeError(RuntimeErrorMode.FAIL_OPEN);
        runtime.on("POST", EVENTS, 500, "{\"error\":\"backend error: locked\"}");
        var model = StubChatModel.answering("hello");

        assertThat(call(chatClient(model), "hi")).isEqualTo("hello");
        assertThat(model.calls).hasValue(1);
    }
}
