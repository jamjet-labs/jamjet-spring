package dev.jamjet.spring.test.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.langchain4j.JamjetChatMemoryStore;
import dev.jamjet.langchain4j.JamjetDurableAgent;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import dev.jamjet.spring.advisor.JamjetAuditAdvisor;
import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import dev.jamjet.spring.approval.ApprovalWaitRegistry;
import dev.jamjet.spring.approval.JamjetApprovalController;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import dev.jamjet.spring.test.JamjetTestContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integration against a real JamJet runtime: nothing here is stubbed on the
 * runtime side. Starts {@code jamjetdev/jamjet} with Testcontainers, or uses the
 * runtime at {@code JAMJET_IT_RUNTIME_URL} (dev mode) when that is set.
 *
 * <p>Every assertion is on a side effect read back from the runtime, never on
 * the absence of an exception: the advisors' old failure was exactly that they
 * threw nothing and recorded nothing.
 */
class RuntimeContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JamjetTestContainer container;
    private static String runtimeUrl;

    @BeforeAll
    static void startRuntime() {
        String external = System.getenv("JAMJET_IT_RUNTIME_URL");
        if (external != null && !external.isBlank()) {
            runtimeUrl = external;
        } else {
            String tag = System.getenv().getOrDefault("JAMJET_IT_RUNTIME_TAG", JamjetTestContainer.DEFAULT_TAG);
            container = new JamjetTestContainer(tag);
            container.start();
            runtimeUrl = container.getRuntimeUrl();
        }
    }

    @AfterAll
    static void stopRuntime() {
        if (container != null) {
            container.stop();
        }
    }

    private static JamjetProperties props() {
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtimeUrl);
        props.getApproval().setTimeout("30s");
        return props;
    }

    private static ChatModel answering(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }
        };
    }

    /** The newest execution matching the predicate, found the way an operator would: GET /executions. */
    private static ExecutionState findExecution(JamjetRuntimeClient client, Predicate<ExecutionState> match) {
        ExecutionState best = null;
        for (int offset = 0; ; offset += 100) {
            var page = client.listExecutions(null, 100, offset);
            for (var e : page) {
                if (match.test(e) && (best == null || e.startedAt().isAfter(best.startedAt()))) {
                    best = e;
                }
            }
            if (page.size() < 100) {
                break;
            }
        }
        assertThat(best).as("an execution matching the call exists on the runtime").isNotNull();
        return best;
    }

    private static List<String> externalKeys(List<ExecutionEvent> events) {
        return events.stream()
                .filter(e -> "external_event_received".equals(e.type()))
                .map(e -> (String) e.field("correlation_key"))
                .toList();
    }

    private static ExecutionEvent external(List<ExecutionEvent> events, String key) {
        return events.stream().filter(e -> e.isExternalEvent(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " event"));
    }

    @Test
    void anAdvisedChatClientCallCreatesAnExecutionWithEvents() {
        var props = props();
        var client = new JamjetRuntimeClient(props);
        var chat = ChatClient.builder(answering("forty-two"))
                .defaultAdvisors(new JamjetDurabilityAdvisor(client, props), new JamjetAuditAdvisor(client, props))
                .build();
        String session = "it-" + UUID.randomUUID();

        String answer = chat.prompt("what is six times seven?")
                .advisors(a -> a.param(JamjetDurabilityAdvisor.SESSION_ID_KEY, session))
                .call().content();

        assertThat(answer).isEqualTo("forty-two");
        var execution = findExecution(client, e -> session.equals(
                e.initialInput() == null ? null : e.initialInput().get("session_id")));
        assertThat(execution.workflowId()).isEqualTo("spring-ai-chat");
        assertThat(execution.workflowVersion()).isEqualTo("1.0.0");

        var events = client.listEvents(execution.executionId());
        assertThat(events).extracting(ExecutionEvent::type).contains("workflow_started", "node_scheduled");
        assertThat(externalKeys(events))
                .containsSubsequence("spring_ai.prompt", "spring_ai.response", "spring_ai.completed");
        assertThat(external(events, "spring_ai.prompt").payload()).containsEntry("content", "what is six times seven?");
        assertThat(external(events, "spring_ai.response").payload()).containsEntry("content", "forty-two");
        assertThat(external(events, "spring_ai.completed").payload()).containsEntry("status", "completed");

        // A second application instance registers the same, constant IR: the
        // runtime treats it as a no-op and the call is recorded as before.
        var restarted = ChatClient.builder(answering("again"))
                .defaultAdvisors(new JamjetDurabilityAdvisor(new JamjetRuntimeClient(props), props))
                .build();
        assertThat(restarted.prompt("hi").call().content()).isEqualTo("again");
    }

    @Test
    void anApplicationSideApprovalRoundTripsThroughTheRuntime() throws Exception {
        var props = props();
        var client = new JamjetRuntimeClient(props);
        var registry = new ApprovalWaitRegistry();
        var controller = new JamjetApprovalController(client, registry);
        var chat = ChatClient.builder(answering("wire sent"))
                .defaultAdvisors(new JamjetDurabilityAdvisor(client, props),
                        new JamjetApprovalAdvisor(client, props, registry))
                .build();

        var call = CompletableFuture.supplyAsync(() -> chat.prompt("send the wire")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (registry.allPending().isEmpty()) {
            assertThat(call).as("the call pauses instead of finishing").isNotDone();
            assertThat(System.nanoTime()).as("the call pauses for approval").isLessThan(deadline);
            Thread.sleep(20);
        }
        var listed = controller.listPending().getBody();
        assertThat(listed).hasSize(1);
        String executionId = (String) listed.get(0).get("execution_id");
        assertThat(listed.get(0)).containsKey("runtime_requests").doesNotContainKey("runtime_error");

        var resp = controller.handleApproval(executionId, ApprovalDecision.approved("alice", "checked"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("recorded_as", "external_event")
                .containsEntry("released_waiting_call", true);
        assertThat(call.get(20, TimeUnit.SECONDS)).isEqualTo("wire sent");

        var events = client.listEvents(executionId);
        assertThat(externalKeys(events))
                .containsSubsequence("spring_ai.approval_requested", "spring_ai.approval_decision", "spring_ai.completed");
        assertThat(external(events, "spring_ai.approval_decision").payload())
                .containsEntry("decision", "approved").containsEntry("user_id", "alice");
    }

    @Test
    void aRuntimeHeldApprovalIsListedWithItsDigestAndDecidedThroughTheController() throws Exception {
        var props = props();
        var client = new JamjetRuntimeClient(props);
        var registry = new ApprovalWaitRegistry();
        var controller = new JamjetApprovalController(client, registry);

        // A workflow the runtime gates itself: an agent tool dispatch node whose
        // policy requires approval for send_wire. Claiming it makes the runtime
        // record a pending approval bound to the exact calls.
        String workflowId = "it-gated-" + UUID.randomUUID();
        var ir = new java.util.LinkedHashMap<String, Object>();
        ir.put("workflow_id", workflowId);
        ir.put("version", "1.0.0");
        ir.put("state_schema", "");
        ir.put("start_node", "dispatch");
        ir.put("nodes", Map.of("dispatch", Map.of("id", "dispatch", "kind", Map.of(
                "type", "java_fn", "class_name", "it.Tools", "method", "dispatch",
                "output_schema", "", "agent_tool_dispatch", true))));
        ir.put("edges", List.of());
        for (String empty : List.of("retry_policies", "models", "tools", "mcp_servers", "remote_agents")) {
            ir.put(empty, Map.of());
        }
        ir.put("policy", Map.of("require_approval_for", List.of("send_wire")));
        client.createWorkflow(ir);
        String executionId = client.startExecution(workflowId, "1.0.0", Map.of("tool_calls",
                List.of(Map.of("name", "send_wire", "arguments", Map.of("amount", 100))))).executionId();
        claimUntilHeld(executionId);

        var view = client.listApprovals(executionId);
        assertThat(view.pending()).hasSize(1);
        String digest = view.pending().get(0).actionDigest();
        assertThat(digest).as("the runtime binds the request to a digest").isNotBlank();

        // A call in this application waiting on that execution.
        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval(executionId, Duration.ofSeconds(30));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        while (!registry.isPending(executionId)) {
            Thread.sleep(10);
        }

        @SuppressWarnings("unchecked")
        var requests = (List<Map<String, Object>>) controller.listPending().getBody().get(0).get("runtime_requests");
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("node_id", "dispatch");
            assertThat(r).containsEntry("action_digest", digest);
        });

        // A refusal comes back with the runtime's own status and message, and
        // the waiting call is released at once rather than timing out.
        var refused = controller.handleApproval(executionId,
                new ApprovalDecision("approved", "no-such-node", "alice", null, digest));
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat((String) refused.getBody().get("error")).contains("no-such-node");
        assertThat(waiting.get(5, TimeUnit.SECONDS).isApproved()).isFalse();

        var approved = controller.handleApproval(executionId,
                new ApprovalDecision("approved", "dispatch", "alice", "reviewed", digest));
        assertThat(approved.getStatusCode().value()).isEqualTo(200);
        assertThat(approved.getBody()).containsEntry("recorded_as", "runtime_approval")
                .containsEntry("accepted", true).containsEntry("node_id", "dispatch");
        var after = client.listApprovals(executionId);
        assertThat(after.pending()).isEmpty();
        assertThat(after.decided()).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("status", "approved").containsEntry("user_id", "alice"));
    }

    @Test
    void theChatMemoryStorePersistsAndReadsBack() {
        var client = new JamjetRuntimeClient(props());
        String memoryId = "it-memory-" + UUID.randomUUID();
        var messages = List.of(UserMessage.from("my name is Ada"), AiMessage.from("Hello Ada"));

        new JamjetChatMemoryStore(client).updateMessages(memoryId, messages);

        // A fresh store, as after a restart, finds the memory on the runtime.
        var reopened = new JamjetChatMemoryStore(new JamjetRuntimeClient(props()));
        assertThat(reopened.getMessages(memoryId)).isEqualTo(messages);

        reopened.deleteMessages(memoryId);
        assertThat(new JamjetChatMemoryStore(client).getMessages(memoryId)).isEmpty();
    }

    interface Assistant {
        String answer(String question);
    }

    @Test
    void aWrappedLangChain4jServiceIsRecorded() {
        var client = new JamjetRuntimeClient(props());
        String marker = "it-" + UUID.randomUUID();
        Assistant durable = JamjetDurableAgent.wrap(q -> "answer to " + q, Assistant.class, client);

        assertThat(durable.answer(marker)).isEqualTo("answer to " + marker);

        var execution = findExecution(client, e -> JamjetDurableAgent.WORKFLOW_ID.equals(e.workflowId())
                && String.valueOf(e.initialInput().get("args")).contains(marker));
        var completed = external(client.listEvents(execution.executionId()), JamjetDurableAgent.COMPLETED_EVENT);
        assertThat(completed.payload()).containsEntry("status", "completed")
                .containsEntry("result", "answer to " + marker);
    }

    @Test
    void theAuditQueryAnswersWithTheRuntimeEnvelope() {
        var page = new JamjetRuntimeClient(props()).queryAudit(null, null, null, 10, 0);

        assertThat(page).isNotNull();
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.items()).isNotNull();
    }

    /** Claim the gated work item until the runtime records its approval request. */
    private static void claimUntilHeld(String executionId) throws Exception {
        var http = HttpClient.newHttpClient();
        var client = new JamjetRuntimeClient(props());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (client.listApprovals(executionId).pending().isEmpty()) {
            assertThat(System.nanoTime()).as("the runtime holds the gated node").isLessThan(deadline);
            var resp = http.send(HttpRequest.newBuilder(URI.create(runtimeUrl + "/work-items/claim"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"worker_id\":\"it\",\"queue_types\":[\"java_tool\"]}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = JSON.readTree(resp.body());
            // A shared runtime may hold other java_tool items; only ours must be withheld.
            String claimedFor = body.path("work_item").path("execution_id").asText("").replace("-", "");
            assertThat(claimedFor)
                    .as("a gated dispatch is withheld, never handed out before approval")
                    .isNotEqualTo(executionId.replace("exec_", "").replace("-", ""));
            Thread.sleep(50);
        }
    }
}
