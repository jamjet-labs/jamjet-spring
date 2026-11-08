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
import dev.jamjet.spring.approval.ReviewDecision;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCallWorkflow;
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
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The integration against a real JamJet runtime: nothing here is stubbed on the
 * runtime side. Starts {@code jamjetdev/jamjet} with Testcontainers, or uses the
 * runtime at {@code JAMJET_IT_RUNTIME_URL} (dev mode) when that is set.
 *
 * <p>Every assertion is on a side effect read back from the runtime, never on
 * the absence of an exception: the advisors' old failure was exactly that they
 * threw nothing and recorded nothing.
 *
 * <p>A runtime with {@code external_fn} nodes records each call under workflow
 * version {@value RecordedCallWorkflow#VERSION}; JamJet 0.5.x, which predates
 * them, under {@value RecordedCallWorkflow#LEGACY_VERSION}. Set
 * {@code JAMJET_IT_EXPECT_VERSION} to the version the runtime under test must
 * get, so CI cannot pass by falling back on the wrong runtime.
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

    /**
     * Version the runtime under test records calls under, asserted against
     * {@code JAMJET_IT_EXPECT_VERSION} when that is set.
     */
    private static String recordedVersion(ExecutionState execution) {
        String expected = System.getenv("JAMJET_IT_EXPECT_VERSION");
        if (expected != null && !expected.isBlank()) {
            assertThat(execution.workflowVersion()).as("the version this runtime records calls under")
                    .isEqualTo(expected);
        }
        assertThat(execution.workflowVersion())
                .isIn(RecordedCallWorkflow.VERSION, RecordedCallWorkflow.LEGACY_VERSION);
        return execution.workflowVersion();
    }

    private static boolean holdsCalls(ExecutionState execution) {
        return RecordedCallWorkflow.VERSION.equals(recordedVersion(execution));
    }

    /** Read the execution until it leaves {@code running} or the deadline passes. */
    private static ExecutionState settled(JamjetRuntimeClient client, String executionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ExecutionState state = client.getExecution(executionId);
        while (state.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            state = client.getExecution(executionId);
        }
        return state;
    }

    private static ExecutionEvent nodeEvent(List<ExecutionEvent> events, String type, String nodeId) {
        return events.stream().filter(e -> type.equals(e.type()) && nodeId.equals(e.nodeId())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " event for node " + nodeId));
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
    void anAdvisedChatClientCallCreatesAnExecutionWithEvents() throws Exception {
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
        recordedVersion(execution);

        var events = client.listEvents(execution.executionId());
        assertThat(events).extracting(ExecutionEvent::type).contains("workflow_started", "node_scheduled");
        assertThat(externalKeys(events))
                .containsSubsequence("spring_ai.prompt", "spring_ai.response", "spring_ai.completed");
        assertThat(external(events, "spring_ai.prompt").payload()).containsEntry("content", "what is six times seven?");
        assertThat(external(events, "spring_ai.response").payload()).containsEntry("content", "forty-two");
        assertThat(external(events, "spring_ai.completed").payload()).containsEntry("status", "completed");

        if (holdsCalls(execution)) {
            // The call's node was claimed by the advisor and completed with the
            // call's outcome, so the execution ended when the call did.
            assertThat(settled(client, execution.executionId()).status()).isEqualTo("completed");
            assertThat(nodeEvent(client.listEvents(execution.executionId()), "node_completed", "chat")
                    .field("output")).isEqualTo(Map.of("status", "completed"));
        }

        // A second application instance registers the same, constant IR: the
        // runtime treats it as a no-op and the call is recorded as before.
        var restarted = ChatClient.builder(answering("again"))
                .defaultAdvisors(new JamjetDurabilityAdvisor(new JamjetRuntimeClient(props), props))
                .build();
        assertThat(restarted.prompt("hi").call().content()).isEqualTo("again");
    }

    @Test
    void theExecutionRunsExactlyAsLongAsTheCall() throws Exception {
        var props = props();
        var client = new JamjetRuntimeClient(props);
        var modelEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ChatModel slow = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                modelEntered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
        };
        String session = "it-running-" + UUID.randomUUID();
        try (var advisor = new JamjetDurabilityAdvisor(client, props)) {
            var chat = ChatClient.builder(slow).defaultAdvisors(advisor).build();
            var call = CompletableFuture.supplyAsync(() -> chat.prompt("take your time")
                    .advisors(a -> a.param(JamjetDurabilityAdvisor.SESSION_ID_KEY, session)).call().content());
            assertThat(modelEntered.await(20, TimeUnit.SECONDS)).as("the model was called").isTrue();

            var execution = findExecution(client, e -> session.equals(
                    e.initialInput() == null ? null : e.initialInput().get("session_id")));
            boolean holds = holdsCalls(execution);
            if (holds) {
                // Held by the advisor while the model runs: not completed early.
                Thread.sleep(300);
                assertThat(client.getExecution(execution.executionId()).status()).isEqualTo("running");
            }
            release.countDown();
            assertThat(call.get(20, TimeUnit.SECONDS)).isEqualTo("done");
            if (holds) {
                assertThat(settled(client, execution.executionId()).status()).isEqualTo("completed");
            }
        }
    }

    @Test
    void aFailedCallFailsItsExecution() throws Exception {
        var props = props();
        var client = new JamjetRuntimeClient(props);
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("model unavailable");
            }
        };
        String session = "it-failed-" + UUID.randomUUID();
        try (var advisor = new JamjetDurabilityAdvisor(client, props)) {
            var chat = ChatClient.builder(broken).defaultAdvisors(advisor).build();
            assertThatThrownBy(() -> chat.prompt("hi")
                    .advisors(a -> a.param(JamjetDurabilityAdvisor.SESSION_ID_KEY, session)).call().content())
                    .hasMessageContaining("model unavailable");
        }
        var execution = findExecution(client, e -> session.equals(
                e.initialInput() == null ? null : e.initialInput().get("session_id")));
        assertThat(external(client.listEvents(execution.executionId()), "spring_ai.completed").payload())
                .containsEntry("status", "failed");
        if (holdsCalls(execution)) {
            assertThat(settled(client, execution.executionId()).status()).isEqualTo("failed");
            assertThat((String) nodeEvent(client.listEvents(execution.executionId()), "node_failed", "chat")
                    .field("error")).contains("model unavailable");
        }
    }

    /**
     * Two instances of one application (two runtime clients that do not share
     * this JVM's routing, as two processes would not) start calls at the same
     * time. A claim takes the oldest node on the queue, so each instance claims
     * nodes of the other's calls and settles them from the outcome event; every
     * execution must still end completed.
     */
    @Test
    void concurrentCallsFromTwoInstancesAllEndCompleted() throws Exception {
        // Dev mode ignores the token; a different one keeps the two clients from
        // handing nodes to each other inside this JVM.
        concurrentCalls("second-instance");
    }

    /** As above, with both advisors in one JVM on the same runtime and token: nodes are handed over directly. */
    @Test
    void concurrentCallsFromTwoAdvisorsInOneJvmAllEndCompleted() throws Exception {
        concurrentCalls(null);
    }

    private void concurrentCalls(String otherToken) throws Exception {
        var base = props();
        var other = props();
        other.setApiToken(otherToken);
        String marker = "it-concurrent-" + UUID.randomUUID();
        int perInstance = 6;
        ExecutorService pool = Executors.newFixedThreadPool(2 * perInstance);
        try (var a = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(base), base);
             var b = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(other), other)) {
            var chats = List.of(ChatClient.builder(answering("ok")).defaultAdvisors(a).build(),
                    ChatClient.builder(answering("ok")).defaultAdvisors(b).build());
            var start = new CountDownLatch(1);
            var calls = new ArrayList<CompletableFuture<String>>();
            for (int i = 0; i < 2 * perInstance; i++) {
                var chat = chats.get(i % 2);
                String session = marker + "-" + i;
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return chat.prompt("hi").advisors(p -> p.param(JamjetDurabilityAdvisor.SESSION_ID_KEY, session))
                            .call().content();
                }, pool));
            }
            start.countDown();
            for (var call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo("ok");
            }
            var client = new JamjetRuntimeClient(base);
            for (int i = 0; i < 2 * perInstance; i++) {
                String session = marker + "-" + i;
                var execution = findExecution(client, e -> session.equals(
                        e.initialInput() == null ? null : e.initialInput().get("session_id")));
                if (holdsCalls(execution)) {
                    assertThat(settled(client, execution.executionId()).status())
                            .as("execution of call %d", i).isEqualTo("completed");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Skip unless the runtime has {@code external_fn} nodes (0.5.x does not). */
    private static void assumeExternalFn() {
        var client = new JamjetRuntimeClient(props());
        try {
            client.createWorkflow(JamjetDurabilityAdvisor.workflowIr("it-probe-" + UUID.randomUUID()));
        } catch (JamjetClientException e) {
            assumeTrue(!e.getRuntimeMessage().contains("unknown variant `external_fn`"),
                    "this runtime predates external_fn nodes (0.5.x); nothing is claimed on it");
            throw e;
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Run {@code body} with the dev tenant's policy set to {@code policy}, then clear it. */
    private static void withTenantPolicy(Map<String, Object> policy, ThrowingRunnable body) throws Exception {
        var http = HttpClient.newHttpClient();
        java.util.function.Function<Object, Integer> put = p -> {
            try {
                return http.send(HttpRequest.newBuilder(URI.create(runtimeUrl + "/tenants/default"))
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("policy", p))))
                                .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        assertThat(put.apply(policy)).as("tenant policy set").isEqualTo(200);
        try {
            body.run();
        } finally {
            put.apply(Map.of());
        }
    }

    private static ChatModel counting(java.util.concurrent.atomic.AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ran"))));
            }
        };
    }

    private static String callTool(String workflowId) {
        return RecordedCallWorkflow.queueFor(workflowId) + "/" + RecordedCallWorkflow.CALL_HANDLER;
    }

    @Test
    void aCallWhoseNodeThePolicyBlocksIsRefusedBeforeTheModelRuns() throws Exception {
        assumeExternalFn();
        String workflowId = "it-blocked-" + UUID.randomUUID();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        withTenantPolicy(Map.of("blocked_tools", List.of(callTool(workflowId))), () -> {
            var props = props();
            props.setWorkflowId(workflowId);
            try (var advisor = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(props), props)) {
                var chat = ChatClient.builder(counting(modelCalls)).defaultAdvisors(advisor).build();
                assertThatThrownBy(() -> chat.prompt("hi").call().content())
                        .isInstanceOf(dev.jamjet.spring.JamjetGovernanceException.class)
                        .hasMessageContaining("policy blocked");
            }
        });
        assertThat(modelCalls).as("the model never ran").hasValue(0);
        var client = new JamjetRuntimeClient(props());
        var execution = findExecution(client, e -> workflowId.equals(e.workflowId()));
        assertThat(settled(client, execution.executionId()).status()).isIn("failed", "cancelled");
    }

    @Test
    void aCallWhoseNodeThePolicyHoldsForApprovalIsRefusedAndCancelled() throws Exception {
        assumeExternalFn();
        String workflowId = "it-held-" + UUID.randomUUID();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        withTenantPolicy(Map.of("require_approval_for", List.of(callTool(workflowId))), () -> {
            var props = props();
            props.setWorkflowId(workflowId);
            try (var advisor = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(props), props)) {
                var chat = ChatClient.builder(counting(modelCalls)).defaultAdvisors(advisor).build();
                assertThatThrownBy(() -> chat.prompt("hi").call().content())
                        .isInstanceOf(dev.jamjet.spring.JamjetGovernanceException.class)
                        .hasMessageContaining("for approval");
            }
        });
        assertThat(modelCalls).hasValue(0);
        var client = new JamjetRuntimeClient(props());
        var execution = findExecution(client, e -> workflowId.equals(e.workflowId()));
        assertThat(settled(client, execution.executionId()).status()).isEqualTo("cancelled");
    }

    /**
     * Calls from two instances start together under a policy that holds every
     * one of their nodes. A claim can take the other call's node, so the gate's
     * verdict for a call's node may land on another instance's claim; no call may
     * run on that account.
     */
    @Test
    void concurrentCallsWhoseNodesThePolicyHoldsAreAllRefused() throws Exception {
        assumeExternalFn();
        String workflowId = "it-held-concurrent-" + UUID.randomUUID();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        withTenantPolicy(Map.of("require_approval_for", List.of(callTool(workflowId))), () -> {
            var first = props();
            first.setWorkflowId(workflowId);
            var second = props();
            second.setWorkflowId(workflowId);
            second.setApiToken("second-instance");
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try (var a = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(first), first);
                 var b = new JamjetDurabilityAdvisor(new JamjetRuntimeClient(second), second)) {
                var chats = List.of(ChatClient.builder(counting(modelCalls)).defaultAdvisors(a).build(),
                        ChatClient.builder(counting(modelCalls)).defaultAdvisors(b).build());
                var start = new CountDownLatch(1);
                var calls = new ArrayList<CompletableFuture<Throwable>>();
                for (int i = 0; i < 6; i++) {
                    var chat = chats.get(i % 2);
                    calls.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                            chat.prompt("hi").call().content();
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    }, pool));
                }
                start.countDown();
                for (var call : calls) {
                    assertThat(call.get(60, TimeUnit.SECONDS))
                            .isInstanceOf(dev.jamjet.spring.JamjetGovernanceException.class);
                }
            } finally {
                pool.shutdownNow();
            }
        });
        assertThat(modelCalls).as("no call ran on a node the policy holds").hasValue(0);
    }

    /**
     * The same, made deterministic: another instance's call started first, so the
     * first claim takes its node, which the policy holds, and hands out nothing.
     * Nothing has been recorded about this call's own node yet; the call must not
     * take that as a go-ahead.
     */
    @Test
    void aHoldOnAnotherCallsNodeIsNotTakenAsAGoAheadForThisCall() throws Exception {
        assumeExternalFn();
        String workflowId = "it-held-first-" + UUID.randomUUID();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        var props = props();
        props.setWorkflowId(workflowId);
        var client = new JamjetRuntimeClient(props);
        withTenantPolicy(Map.of("require_approval_for", List.of(callTool(workflowId))), () -> {
            client.createWorkflow(JamjetDurabilityAdvisor.workflowIr(workflowId));
            String other = client.startExecution(workflowId, RecordedCallWorkflow.VERSION,
                    Map.of("session_id", "elsewhere")).executionId();
            try (var advisor = new JamjetDurabilityAdvisor(client, props)) {
                var chat = ChatClient.builder(counting(modelCalls)).defaultAdvisors(advisor).build();
                assertThatThrownBy(() -> chat.prompt("hi").call().content())
                        .isInstanceOf(dev.jamjet.spring.JamjetGovernanceException.class)
                        .hasMessageContaining("for approval");
            }
            assertThat(client.listApprovals(other).pending()).as("the other call's node was held first").hasSize(1);
        });
        assertThat(modelCalls).as("the model never ran").hasValue(0);
    }

    /**
     * A node claimed for a call that another instance runs (here, an execution
     * started directly, whose node nobody claimed yet) is held, marked, and
     * completed from that call's outcome event.
     */
    @Test
    void aNodeClaimedForAnotherInstancesCallIsCompletedFromItsOutcome() throws Exception {
        assumeExternalFn();
        String workflowId = "it-foreign-" + UUID.randomUUID();
        var props = props();
        props.setWorkflowId(workflowId);
        var client = new JamjetRuntimeClient(props);
        client.createWorkflow(JamjetDurabilityAdvisor.workflowIr(workflowId));
        String foreign = client.startExecution(workflowId, RecordedCallWorkflow.VERSION,
                Map.of("session_id", "elsewhere")).executionId();

        try (var advisor = new JamjetDurabilityAdvisor(client, props)) {
            var chat = ChatClient.builder(answering("here")).defaultAdvisors(advisor).build();
            assertThat(chat.prompt("hi").call().content()).isEqualTo("here");

            assertThat(externalKeys(client.listEvents(foreign))).as("the holder marks the other call's execution")
                    .contains(dev.jamjet.spring.client.RecordedCalls.HELD_EVENT);
            assertThat(client.getExecution(foreign).status()).isEqualTo("running");

            client.sendExternalEvent(foreign, JamjetDurabilityAdvisor.COMPLETED_EVENT,
                    Map.of("status", "completed", "model", "elsewhere"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (client.getExecution(foreign).isRunning() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(client.getExecution(foreign).status()).isEqualTo("completed");
            assertThat(nodeEvent(client.listEvents(foreign), "node_completed", "chat").field("output"))
                    .isEqualTo(Map.of("status", "completed", "model", "elsewhere"));
        }
    }

    /**
     * Why the workflow has an {@code external_fn} node: a runtime with it refuses
     * the {@code wait} workflow 0.2.x registered, since nothing executes a wait node.
     */
    @Test
    void aRuntimeWithExternalFnRefusesTheWaitNodeWorkflow() {
        var client = new JamjetRuntimeClient(props());
        String workflowId = "it-wait-" + UUID.randomUUID();
        String version;
        try {
            version = client.createWorkflow(JamjetDurabilityAdvisor.workflowIr(workflowId)).version();
        } catch (JamjetClientException e) {
            assumeTrue(!e.getRuntimeMessage().contains("unknown variant `external_fn`"),
                    "this runtime predates external_fn nodes (0.5.x) and accepts the wait node");
            throw e;
        }
        assertThat(version).isEqualTo(RecordedCallWorkflow.VERSION);
        assertThatThrownBy(() -> client.createWorkflow(JamjetDurabilityAdvisor.legacyWorkflowIr(workflowId)))
                .isInstanceOfSatisfying(JamjetClientException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getRuntimeMessage()).contains("cannot execute node(s) 'chat' (wait)");
                });
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

        // The call is made by an authenticated user, as in a web request.
        var call = CompletableFuture.supplyAsync(() -> {
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "requester", "n/a", List.of()));
            org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
            try {
                return chat.prompt("send the wire")
                        .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                        .call().content();
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (registry.allPending().isEmpty()) {
            assertThat(call).as("the call pauses instead of finishing").isNotDone();
            assertThat(System.nanoTime()).as("the call pauses for approval").isLessThan(deadline);
            Thread.sleep(20);
        }
        var listed = pending(controller);
        assertThat(listed).hasSize(1);
        String executionId = (String) listed.get(0).get("execution_id");
        assertThat(listed.get(0)).doesNotContainKeys("runtime_error", "runtime_requests");

        // The reviewer is the authenticated principal (Spring Security in a real app).
        // The reviewer echoes the digest of the prompt they were shown.
        assertThat(listed.get(0)).containsEntry("prompt", "[user] send the wire");
        String digest = (String) listed.get(0).get("content_digest");
        var resp = controller.handleApproval(executionId, ReviewDecision.approved(digest, "checked"), approver("alice"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("recorded_as", "external_event")
                .containsEntry("released_waiting_call", true);
        assertThat(call.get(20, TimeUnit.SECONDS)).isEqualTo("wire sent");

        var events = client.listEvents(executionId);
        assertThat(externalKeys(events))
                .containsSubsequence("spring_ai.approval_requested", "spring_ai.approval_decision", "spring_ai.completed");
        assertThat(external(events, "spring_ai.approval_decision").payload())
                .containsEntry("decision", "approved").containsEntry("approver", "alice")
                .containsEntry("authenticated", true).containsEntry("content_digest", digest)
                .containsEntry("initiator", "requester");
        var execution = client.getExecution(executionId);
        if (holdsCalls(execution)) {
            // The node was held through the approval wait and completed after the call.
            assertThat(settled(client, executionId).status()).isEqualTo("completed");
        }
    }

    @Test
    void aRuntimeHeldApprovalIsListedReadOnlyAndDecidedWithTheReviewersOwnToken() throws Exception {
        var props = props();
        var client = new JamjetRuntimeClient(props);
        var registry = new ApprovalWaitRegistry();
        var controller = new JamjetApprovalController(client, registry);

        // A workflow the runtime gates itself: an agent tool dispatch node whose
        // policy requires approval for send_wire. Claiming it makes the runtime
        // record a pending approval bound to the exact calls. The runtime accepts
        // the dispatch marker only on the ADK dispatcher's coordinates.
        String workflowId = "it-gated-" + UUID.randomUUID();
        var ir = new java.util.LinkedHashMap<String, Object>();
        ir.put("workflow_id", workflowId);
        ir.put("version", "1.0.0");
        ir.put("state_schema", "");
        ir.put("start_node", "dispatch");
        ir.put("nodes", Map.of("dispatch", Map.of("id", "dispatch", "kind", Map.of(
                "type", "python_fn", "module", "jamjet.agents.tool_runtime", "function", "dispatch_tool_calls",
                "output_schema", "", "agent_tool_dispatch", true))));
        ir.put("edges", List.of());
        for (String empty : List.of("retry_policies", "models", "tools", "mcp_servers", "remote_agents")) {
            ir.put(empty, Map.of());
        }
        ir.put("policy", Map.of("require_approval_for", List.of("send_wire")));
        client.createWorkflow(ir);
        String executionId = client.startExecution(workflowId, "1.0.0", Map.of("tool_calls",
                List.of(Map.of("name", "send_wire", "arguments", Map.of("amount", 100))))).executionId();
        // Holding a claimed agent tool dispatch for approval is runtime behaviour
        // newer than the 0.5.0 image (it reads the frozen calls from the claim
        // payload). On an older runtime this test cannot set up its precondition.
        assumeTrue(claimUntilHeld(executionId),
                "this runtime does not hold agent tool dispatches for approval at claim time "
                        + "(jamjetdev/jamjet 0.5.0 predates it); run against a newer jamjet-server");

        String digest = client.listApprovals(executionId).pending().get(0).actionDigest();
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

        var item = pending(controller).get(0);
        @SuppressWarnings("unchecked")
        var requests = (List<Map<String, Object>>) item.get("runtime_requests");
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("node_id", "dispatch").containsEntry("action_digest", digest);
            assertThat((String) r.get("decide_with")).contains("--action-digest " + digest);
        });

        // Listing never decided anything on the runtime.
        assertThat(client.listApprovals(executionId).pending()).hasSize(1);

        // The reviewer decides with their own token. In dev mode the runtime has
        // no tokens and records user_id; with auth on it records the token's
        // name and ignores user_id. This assertion is the dev-mode form.
        client.approveExecution(executionId, new ApprovalDecision("approved", "dispatch", "alice", "reviewed", digest));
        // GET /approvals serves a projection that lags the write path by up to
        // one projector tick (~500 ms), so read until it catches up.
        var after = client.listApprovals(executionId);
        long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!after.pending().isEmpty() && System.nanoTime() < settle) {
            Thread.sleep(100);
            after = client.listApprovals(executionId);
        }
        assertThat(after.pending()).isEmpty();
        assertThat(after.decided()).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("status", "approved").containsEntry("user_id", "alice"));

        registry.complete(executionId, ApprovalDecision.rejected("it", "cleanup"));
        waiting.get(5, TimeUnit.SECONDS);
    }

    @Test
    void theChatMemoryStorePersistsAndReadsBack() {
        var client = new JamjetRuntimeClient(props());
        String memoryId = "it-memory-" + UUID.randomUUID();
        var messages = List.of(UserMessage.from("my name is Ada"), AiMessage.from("Hello Ada"));

        new JamjetChatMemoryStore(client, "it-app").updateMessages(memoryId, messages);

        // A fresh store, as after a restart, finds the memory on the runtime.
        var reopened = new JamjetChatMemoryStore(new JamjetRuntimeClient(props()), "it-app");
        assertThat(new JamjetChatMemoryStore(client, "another-app").getMessages(memoryId))
                .as("another application on the tenant does not see it").isEmpty();
        assertThat(reopened.getMessages(memoryId)).isEqualTo(messages);

        var memory = findExecution(client, e -> JamjetChatMemoryStore.WORKFLOW_ID.equals(e.workflowId())
                && memoryId.equals(e.initialInput().get("memory_id")));
        if (holdsCalls(memory)) {
            // Nothing claims the memory's node, so its execution stays open.
            assertThat(client.getExecution(memory.executionId()).status()).isEqualTo("running");
        }

        reopened.deleteMessages(memoryId);
        assertThat(new JamjetChatMemoryStore(client, "it-app").getMessages(memoryId)).isEmpty();
    }

    interface Assistant {
        String answer(String question);
    }

    @Test
    void aWrappedLangChain4jServiceIsRecorded() throws Exception {
        var client = new JamjetRuntimeClient(props());
        String marker = "it-" + UUID.randomUUID();
        Assistant durable = JamjetDurableAgent.wrap(q -> "answer to " + q, Assistant.class, client);

        assertThat(durable.answer(marker)).isEqualTo("answer to " + marker);

        var execution = findExecution(client, e -> JamjetDurableAgent.WORKFLOW_ID.equals(e.workflowId())
                && String.valueOf(e.initialInput().get("args")).contains(marker));
        var completed = external(client.listEvents(execution.executionId()), JamjetDurableAgent.COMPLETED_EVENT);
        assertThat(completed.payload()).containsEntry("status", "completed")
                .containsEntry("result", "answer to " + marker);
        if (holdsCalls(execution)) {
            assertThat(settled(client, execution.executionId()).status()).isEqualTo("completed");
        }
    }

    @Test
    void theAuditQueryAnswersWithTheRuntimeEnvelope() {
        var page = new JamjetRuntimeClient(props()).queryAudit(null, null, null, 10, 0);

        assertThat(page).isNotNull();
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.items()).isNotNull();
    }

    /** Claim the gated work item until the runtime records its approval request. */
    private static boolean claimUntilHeld(String executionId) throws Exception {
        var http = HttpClient.newHttpClient();
        var client = new JamjetRuntimeClient(props());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.listApprovals(executionId).pending().isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            var resp = http.send(HttpRequest.newBuilder(URI.create(runtimeUrl + "/work-items/claim"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"worker_id\":\"it\",\"queue_types\":[\"python_tool\"]}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = JSON.readTree(resp.body());
            // A shared runtime may hold other python_tool items; only ours must be withheld.
            String claimedFor = body.path("work_item").path("execution_id").asText("").replace("-", "");
            assertThat(claimedFor)
                    .as("a gated dispatch is withheld, never handed out before approval")
                    .isNotEqualTo(executionId.replace("exec_", "").replace("-", ""));
            Thread.sleep(50);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pending(JamjetApprovalController c) {
        // In a real application the reviewer is the request's Spring Security principal.
        return (List<Map<String, Object>>) c.listPending(approver("reviewer")).getBody();
    }

    /** A reviewer as Spring Security would present one: authenticated, holding the approver authority. */
    private static java.security.Principal approver(String name) {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name, "n/a",
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(
                        dev.jamjet.spring.approval.ReviewPolicy.DEFAULT_AUTHORITY));
    }
}
