package dev.jamjet.spring.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.JamjetRecordingException;
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

/** What happens after the model ran, and on the streaming path. */
class JamjetPostCallAndStreamingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENTS = "/executions/exec_1/external-event";

    private RecordingRuntime runtime;
    private JamjetProperties props;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}");
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private ChatClient chat(StubChatModel model) {
        var client = new JamjetRuntimeClient(props);
        return ChatClient.builder(model)
                .defaultAdvisors(new JamjetDurabilityAdvisor(client, props), new JamjetAuditAdvisor(client, props))
                .build();
    }

    private List<JsonNode> events() throws Exception {
        var out = new ArrayList<JsonNode>();
        for (var r : runtime.requests()) {
            if (r.method().equals("POST") && r.path().equals(EVENTS)) {
                out.add(JSON.readTree(r.body()));
            }
        }
        return out;
    }

    private JsonNode event(String key) throws Exception {
        return events().stream().filter(e -> e.get("correlation_key").asText().equals(key)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no " + key));
    }

    // ── After the model ran ─────────────────────────────────────────────────

    @Test
    void aRecordFailureAfterTheModelRanSaysTheCallCompleted() {
        // Prompt event accepted, response event refused: the model has run by then.
        props.getAudit().setEnabled(true);
        var model = StubChatModel.answering("hello");
        var client = new JamjetRuntimeClient(props);
        var chat = ChatClient.builder(model).defaultAdvisors(new JamjetDurabilityAdvisor(client, props)).build();
        runtime.on("POST", EVENTS, 503, "{\"error\":\"unavailable\"}");

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(JamjetRecordingException.class)
                .isNotInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("model call completed")
                .hasMessageContaining("unavailable")
                .hasCauseInstanceOf(dev.jamjet.spring.client.JamjetClientException.class);
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void whenTheModelFailedAndTheRecordFailsTheModelErrorIsWhatTheCallerSees() {
        var client = new JamjetRuntimeClient(props);
        var chat = ChatClient.builder(StubChatModel.failingWith(new IllegalStateException("model exploded")))
                .defaultAdvisors(new JamjetDurabilityAdvisor(client, props)).build();
        runtime.on("POST", EVENTS, 503, "{\"error\":\"unavailable\"}");

        assertThatThrownBy(() -> chat.prompt("hi").call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model exploded")
                .satisfies(e -> assertThat(e.getSuppressed())
                        .anySatisfy(s -> assertThat(s).isInstanceOf(JamjetRecordingException.class)));
    }

    @Test
    void anAuditResponseThatCannotBeRecordedSaysTheCallCompleted() {
        var model = StubChatModel.answering("hello");
        var client = new JamjetRuntimeClient(props);
        var chat = ChatClient.builder(model).defaultAdvisors(new JamjetAuditAdvisor(client, props)).build();
        // The prompt event succeeds once, then the runtime goes away.
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var flaky = new java.util.concurrent.atomic.AtomicInteger();

        assertThatThrownBy(() -> chat.prompt("hi")
                .advisors(a -> a.param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .advisors(new org.springframework.ai.chat.client.advisor.api.BaseAdvisor() {
                    @Override public String getName() { return "RuntimeGoesAwayAfterThePrompt"; }
                    @Override public int getOrder() { return org.springframework.core.Ordered.LOWEST_PRECEDENCE - 40; }
                    @Override public org.springframework.ai.chat.client.ChatClientRequest before(
                            org.springframework.ai.chat.client.ChatClientRequest r,
                            org.springframework.ai.chat.client.advisor.api.AdvisorChain c) {
                        runtime.on("POST", EVENTS, 503, "{\"error\":\"unavailable\"}");
                        flaky.incrementAndGet();
                        return r;
                    }
                    @Override public org.springframework.ai.chat.client.ChatClientResponse after(
                            org.springframework.ai.chat.client.ChatClientResponse r,
                            org.springframework.ai.chat.client.advisor.api.AdvisorChain c) { return r; }
                })
                .call().content())
                .isInstanceOf(JamjetRecordingException.class)
                .hasMessageContaining("model call completed");
        assertThat(model.calls).hasValue(1);
        assertThat(flaky).hasValue(1);
    }

    // ── Streaming ───────────────────────────────────────────────────────────

    @Test
    void streamingIsRefusedByDefaultInFailClosedMode() {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var model = StubChatModel.answering("hello there world");

        assertThatThrownBy(() -> chat(model).prompt("hi").stream().content().collectList().block())
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("spring.jamjet.allow-streaming");
        assertThat(model.calls).hasValue(0);
        assertThat(runtime.requests()).isEmpty();
    }

    @Test
    void anAllowedStreamRecordsTheWholeTextAndItsOutcome() throws Exception {
        props.setAllowStreaming(true);
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");

        var chunks = chat(StubChatModel.answering("hello there world")).prompt("hi").stream().content()
                .collectList().block();

        assertThat(String.join("", chunks)).isEqualTo("hello there world");
        assertThat(event("spring_ai.response").get("payload").get("content").asText()).isEqualTo("hello there world");
        JsonNode done = event("spring_ai.completed").get("payload");
        assertThat(done.get("status").asText()).isEqualTo("completed");
        assertThat(done.get("streamed").asBoolean()).isTrue();
    }

    @Test
    void aStreamThatFailsRecordsAFailedOutcome() throws Exception {
        props.setAllowStreaming(true);
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");

        assertThatThrownBy(() -> chat(StubChatModel.failingWith(new IllegalStateException("stream broke")))
                .prompt("hi").stream().content().collectList().block())
                .hasMessageContaining("stream broke");

        JsonNode done = event("spring_ai.completed").get("payload");
        assertThat(done.get("status").asText()).isEqualTo("failed");
        assertThat(done.get("error").asText()).contains("stream broke");
    }

    @Test
    void aCancelledStreamRecordsACancelledOutcome() throws Exception {
        props.setAllowStreaming(true);
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");

        var first = chat(StubChatModel.answering("hello there world")).prompt("hi").stream().content()
                .take(1).collectList().block();

        assertThat(first).hasSize(1);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (events().stream().noneMatch(e -> e.get("correlation_key").asText().equals("spring_ai.completed"))) {
            assertThat(System.nanoTime()).as("a cancelled stream records its outcome").isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(event("spring_ai.completed").get("payload").get("status").asText()).isEqualTo("cancelled");
    }
}
