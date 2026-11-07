package dev.jamjet.spring.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.approval.ApprovalWaitRegistry;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JamjetApprovalAdvisorFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENTS = "/executions/exec_1/external-event";

    private RecordingRuntime runtime;
    private JamjetProperties props;
    private ApprovalWaitRegistry registry;
    private StubChatModel model;
    private ChatClient chat;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        props.getApproval().setTimeout("10s");
        registry = new ApprovalWaitRegistry();
        model = StubChatModel.answering("hello");
        var advisor = new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry);
        chat = ChatClient.builder(model).defaultAdvisors(advisor).build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private CompletableFuture<String> pausedCall(String executionId) throws Exception {
        var call = CompletableFuture.supplyAsync(() -> chat.prompt("wire 100")
                .advisors(a -> {
                    a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true);
                    if (executionId != null) {
                        a.param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, executionId);
                    }
                })
                .call().content());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executionId != null && registry.allPending().isEmpty() && !call.isDone()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("call never paused");
            }
            Thread.sleep(5);
        }
        return call;
    }

    @Test
    void recordsTheRequestOnTheExecutionThenRunsOnceApproved() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        var call = pausedCall("exec_1");
        assertThat(model.calls).as("nothing reaches the model before approval").hasValue(0);
        var requested = runtime.requests().stream().filter(r -> r.path().equals(EVENTS)).findFirst().orElseThrow();
        assertThat(JSON.readTree(requested.body()).get("correlation_key").asText())
                .isEqualTo("spring_ai.approval_requested");

        registry.complete("exec_1", ApprovalDecision.approved("alice", null));

        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void anythingButAnExplicitApprovalRejects() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        var call = pausedCall("exec_1");
        registry.complete("exec_1", new ApprovalDecision("yes", null, "alice", null, null));

        assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ApprovalRejectedException.class);
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void aRequiredApprovalWithNoExecutionToApproveAgainstIsRejected() {
        // Durability off or failed open: there is nothing a reviewer can approve,
        // so the gate must not be skipped.
        assertThatThrownBy(() -> chat.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content())
                .isInstanceOf(ApprovalRejectedException.class);
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void aRequestThatCannotBeRecordedIsRefusedWithoutWaiting() {
        runtime.on("POST", EVENTS, 500, "{\"error\":\"backend error: locked\"}");

        assertThatThrownBy(() -> chat.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content())
                .isInstanceOf(dev.jamjet.spring.JamjetGovernanceException.class)
                .hasMessageContaining("backend error: locked");
        assertThat(registry.allPending()).isEmpty();
        assertThat(model.calls).hasValue(0);
    }

    private ChatClient withTimeout(String timeout, String defaultDecision) {
        props.getApproval().setTimeout(timeout);
        if (defaultDecision != null) {
            props.getApproval().setDefaultDecision(defaultDecision);
        }
        var advisor = new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry);
        return ChatClient.builder(model).defaultAdvisors(advisor).build();
    }

    private String unattended(ChatClient client) {
        return client.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content();
    }

    @Test
    void anUnansweredCallIsRejectedByDefaultWhenItTimesOut() {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        assertThatThrownBy(() -> unattended(withTimeout("1s", null)))
                .isInstanceOf(ApprovalRejectedException.class)
                .hasMessageContaining("timed out");
        assertThat(model.calls).as("an unattended call never reaches the model").hasValue(0);
        assertThat(registry.allPending()).isEmpty();
    }

    @Test
    void aTimeoutNeverApprovesSoAnApprovedDefaultFailsAtStartup() {
        props.getApproval().setDefaultDecision("approved");

        assertThatThrownBy(() -> new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.jamjet.approval.default-decision")
                .hasMessageContaining("never approves");
    }

    @Test
    void anUnrecognisedDefaultDecisionFailsAtStartup() {
        props.getApproval().setDefaultDecision("maybe");

        assertThatThrownBy(() -> new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWaitForAnInFlightDecisionCoversTheClientsOwnTimeouts() {
        props.setConnectTimeoutSeconds(3);
        props.setReadTimeoutSeconds(40);

        assertThat(JamjetApprovalAdvisor.decidingGrace(props))
                .as("a decision being recorded cannot outlive the wait for it")
                .isGreaterThan(java.time.Duration.ofSeconds(43));
    }

    @Test
    void theCallersPrincipalIsRecordedAsTheInitiator() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");
        var call = CompletableFuture.supplyAsync(() -> {
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "carol", "n/a", java.util.List.of()));
            org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
            try {
                return chat.prompt("wire 100")
                        .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                                .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                        .call().content();
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!registry.isPending("exec_1")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }

        assertThat(registry.context("exec_1").initiator()).isEqualTo("carol");
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
    }

    @Test
    void theModelReceivesExactlyThePromptTheReviewerApproved() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");
        // An application advisor that rewrites the prompt, ordered between audit and the model.
        var rewriter = new org.springframework.ai.chat.client.advisor.api.BaseAdvisor() {
            @Override public String getName() { return "Rewriter"; }
            @Override public int getOrder() { return org.springframework.core.Ordered.LOWEST_PRECEDENCE - 10; }
            @Override public org.springframework.ai.chat.client.ChatClientRequest before(
                    org.springframework.ai.chat.client.ChatClientRequest r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) {
                return r.mutate().prompt(r.prompt().augmentUserMessage("wire 1000000")).build();
            }
            @Override public org.springframework.ai.chat.client.ChatClientResponse after(
                    org.springframework.ai.chat.client.ChatClientResponse r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) { return r; }
        };
        var advisor = new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry);
        var rewritten = ChatClient.builder(model).defaultAdvisors(advisor, rewriter).build();

        var call = CompletableFuture.supplyAsync(() -> rewritten.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!registry.isPending("exec_1")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        String reviewed = registry.context("exec_1").contentDigest();
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        call.get(5, TimeUnit.SECONDS);

        assertThat(dev.jamjet.spring.approval.ApprovalContent.digest(model.lastPrompt))
                .as("what the model ran is what the reviewer approved").isEqualTo(reviewed);
    }

    @Test
    void aSecondCallWaitingOnTheSameExecutionIsRefusedNotMerged() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");
        var first = pausedCall("exec_1");

        // A second call that names the same execution id (durability off, caller-supplied id).
        assertThatThrownBy(() -> chat.prompt("wire 1000000")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content())
                .isInstanceOf(ApprovalRejectedException.class)
                .hasMessageContaining("already waiting");
        assertThat(registry.context("exec_1").promptPreview()).contains("wire 100").doesNotContain("1000000");

        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
        assertThat(model.calls).as("one decision released one call").hasValue(1);
    }

    /** Asks for the lookup tool on the first round, answers on the second. */
    static final class ToolCallingModel implements org.springframework.ai.chat.model.ChatModel {
        final java.util.List<org.springframework.ai.chat.prompt.Prompt> prompts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            prompts.add(prompt);
            var message = prompts.size() == 1
                    ? org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                            .toolCalls(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "t1", "function", "lookup", "\"acct\"")))
                            .build()
                    : new org.springframework.ai.chat.messages.AssistantMessage("done");
            return new org.springframework.ai.chat.model.ChatResponse(
                    java.util.List.of(new org.springframework.ai.chat.model.Generation(message)));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
        }
    }

    private String awaitPendingDigestOtherThan(String previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            var context = registry.context("exec_1");
            if (context != null && context.contentDigest() != null && !context.contentDigest().equals(previous)) {
                return context.contentDigest();
            }
            assertThat(System.nanoTime()).as("a call paused for a decision").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    /**
     * Spring AI 2.0 runs the tool loop in its ToolCallingAdvisor, outside this
     * advisor. Without the durability advisor there is no per-call approval state,
     * so every model round of a call that uses tools needs its own decision, and
     * the second round's review shows the tool result the model would get. The
     * tool itself runs between the rounds without review.
     */
    @Test
    void withoutTheDurabilityAdvisorEveryRoundOfACallThatUsesToolsNeedsADecision() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");
        var toolModel = new ToolCallingModel();
        var toolRuns = new java.util.concurrent.atomic.AtomicInteger();
        var tool = org.springframework.ai.tool.function.FunctionToolCallback
                .builder("lookup", (java.util.function.Function<String, String>) s -> {
                    toolRuns.incrementAndGet();
                    return "42";
                })
                .description("look up an account").inputType(String.class).build();
        var withTools = ChatClient.builder(toolModel)
                .defaultAdvisors(new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry)).build();

        var call = CompletableFuture.supplyAsync(() -> withTools.prompt("wire 100").toolCallbacks(tool)
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content());

        String first = awaitPendingDigestOtherThan(null);
        assertThat(toolModel.prompts).isEmpty();
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));

        String second = awaitPendingDigestOtherThan(first);
        assertThat(toolRuns).as("the tool ran between the rounds, without a decision of its own").hasValue(1);
        assertThat(toolModel.prompts).hasSize(1);
        assertThat(registry.context("exec_1").promptPreview()).contains("[tool response] lookup 42");
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));

        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(dev.jamjet.spring.approval.ApprovalContent.digest(toolModel.prompts.get(0))).isEqualTo(first);
        assertThat(dev.jamjet.spring.approval.ApprovalContent.digest(toolModel.prompts.get(1))).isEqualTo(second);
    }

    /**
     * Advisors with the same order run in reverse registration order, so one
     * registered before this advisor at {@code LOWEST_PRECEDENCE - 1} would run
     * after the review and could change what the model gets. Such a call is refused.
     */
    @Test
    void aCallIsRefusedWhenAnAdvisorWouldRunBetweenTheReviewAndTheModel() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");
        var rewriter = new org.springframework.ai.chat.client.advisor.api.BaseAdvisor() {
            @Override public String getName() { return "Rewriter"; }
            @Override public int getOrder() { return org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1; }
            @Override public org.springframework.ai.chat.client.ChatClientRequest before(
                    org.springframework.ai.chat.client.ChatClientRequest r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) {
                return r.mutate().prompt(r.prompt().augmentUserMessage("wire 1000000")).build();
            }
            @Override public org.springframework.ai.chat.client.ChatClientResponse after(
                    org.springframework.ai.chat.client.ChatClientResponse r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) { return r; }
        };
        var advisor = new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry);
        var rewritten = ChatClient.builder(model).defaultAdvisors(rewriter, advisor).build();

        var call = CompletableFuture.supplyAsync(() -> rewritten.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!call.isDone() && !registry.isPending("exec_1")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        if (registry.isPending("exec_1")) {
            registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        }

        assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ApprovalRejectedException.class)
                .hasMessageContaining("Rewriter");
        assertThat(model.calls).as("nothing unreviewed reaches the model").hasValue(0);
    }

    @Test
    void aStreamIsRefusedWhenAnAdvisorWouldRunBetweenTheReviewAndTheModel() {
        var rewriter = new org.springframework.ai.chat.client.advisor.api.BaseAdvisor() {
            @Override public String getName() { return "Rewriter"; }
            @Override public int getOrder() { return org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1; }
            @Override public org.springframework.ai.chat.client.ChatClientRequest before(
                    org.springframework.ai.chat.client.ChatClientRequest r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) {
                return r.mutate().prompt(r.prompt().augmentUserMessage("wire 1000000")).build();
            }
            @Override public org.springframework.ai.chat.client.ChatClientResponse after(
                    org.springframework.ai.chat.client.ChatClientResponse r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) { return r; }
        };
        var advisor = new JamjetApprovalAdvisor(new JamjetRuntimeClient(props), props, registry);
        var rewritten = ChatClient.builder(model).defaultAdvisors(rewriter, advisor).build();

        assertThatThrownBy(() -> rewritten.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .stream().content().collectList().block(java.time.Duration.ofSeconds(5)))
                .isInstanceOf(ApprovalRejectedException.class)
                .hasMessageContaining("Rewriter");
        assertThat(registry.isPending("exec_1")).isFalse();
        assertThat(model.calls).hasValue(0);
    }

    private org.springframework.ai.tool.ToolCallback lookup(java.util.concurrent.atomic.AtomicInteger runs) {
        return org.springframework.ai.tool.function.FunctionToolCallback
                .builder("lookup", (java.util.function.Function<String, String>) s -> {
                    runs.incrementAndGet();
                    return "42";
                })
                .description("look up an account").inputType(String.class).build();
    }

    private ChatClient recorded(org.springframework.ai.chat.model.ChatModel chatModel,
                                org.springframework.ai.chat.client.advisor.api.Advisor... more) {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}")
                .on("POST", EVENTS, 200, "{\"accepted\":true}");
        var client = new JamjetRuntimeClient(props);
        var advisors = new java.util.ArrayList<org.springframework.ai.chat.client.advisor.api.Advisor>(java.util.List.of(
                new JamjetDurabilityAdvisor(client, props), new JamjetApprovalAdvisor(client, props, registry)));
        advisors.addAll(java.util.List.of(more));
        return ChatClient.builder(chatModel).defaultAdvisors(advisors).build();
    }

    private long approvalRequests() throws Exception {
        long n = 0;
        for (var r : runtime.requests()) {
            if (r.path().equals(EVENTS)
                    && JSON.readTree(r.body()).get("correlation_key").asText().equals("spring_ai.approval_requested")) {
                n++;
            }
        }
        return n;
    }

    /**
     * A recorded call that uses tools needs one decision, as in Spring AI 1.0: the
     * tool loop's next round only adds the tool calls the model asked for and their
     * results, so it continues the approval. The tool calls are not reviewed.
     */
    @Test
    void aRecordedCallThatUsesToolsNeedsOneDecision() throws Exception {
        var toolModel = new ToolCallingModel();
        var toolRuns = new java.util.concurrent.atomic.AtomicInteger();
        var chatClient = recorded(toolModel);

        var call = CompletableFuture.supplyAsync(() -> chatClient.prompt("wire 100").toolCallbacks(lookup(toolRuns))
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content());
        String reviewed = awaitPendingDigestOtherThan(null);
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));

        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(toolModel.prompts).hasSize(2);
        assertThat(toolRuns).hasValue(1);
        assertThat(approvalRequests()).as("one decision for the whole call").isEqualTo(1);
        assertThat(dev.jamjet.spring.approval.ApprovalContent.digest(toolModel.prompts.get(0))).isEqualTo(reviewed);
    }

    private java.util.List<com.fasterxml.jackson.databind.JsonNode> events(String kind) throws Exception {
        var found = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var r : runtime.requests()) {
            if (r.path().equals(EVENTS)) {
                var body = JSON.readTree(r.body());
                if (body.get("correlation_key").asText().equals(kind)) {
                    found.add(body);
                }
            }
        }
        return found;
    }

    /** A round that goes through on an earlier decision is recorded, with the digest of what the model got. */
    @Test
    void aContinuedRoundIsRecordedOnTheRuntime() throws Exception {
        var toolModel = new ToolCallingModel();
        var chatClient = recorded(toolModel);

        var call = CompletableFuture.supplyAsync(() -> chatClient.prompt("wire 100")
                .toolCallbacks(lookup(new java.util.concurrent.atomic.AtomicInteger()))
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content());
        awaitPendingDigestOtherThan(null);
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("done");

        var continued = events("spring_ai.approval_continued");
        assertThat(continued).hasSize(1);
        assertThat(continued.get(0).toString()).contains(
                dev.jamjet.spring.approval.ApprovalContent.digest(toolModel.prompts.get(1)));
    }

    /** With max-continued-rounds 0 every round is reviewed, and the preview marks what is new. */
    @Test
    void withNoContinuedRoundsEveryRoundIsReviewed() throws Exception {
        props.getApproval().setMaxContinuedRounds(0);
        var toolModel = new ToolCallingModel();
        var chatClient = recorded(toolModel);

        var call = CompletableFuture.supplyAsync(() -> chatClient.prompt("wire 100")
                .toolCallbacks(lookup(new java.util.concurrent.atomic.AtomicInteger()))
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content());
        String first = awaitPendingDigestOtherThan(null);
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        awaitPendingDigestOtherThan(first);
        assertThat(registry.context("exec_1").promptPreview())
                .contains("[--- new since the last approval ---]", "[assistant tool call, already executed] lookup");
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));

        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(approvalRequests()).isEqualTo(2);
        assertThat(events("spring_ai.approval_continued")).isEmpty();
    }

    /** A round that differs in anything but the tool calls and their results is reviewed again. */
    @Test
    void aRoundThatChangesMoreThanTheToolResultsNeedsItsOwnDecision() throws Exception {
        var toolModel = new ToolCallingModel();
        var toolRuns = new java.util.concurrent.atomic.AtomicInteger();
        // Inside the tool loop (after the ToolCallingAdvisor), before the review: rewrites the second round.
        var secondRoundRewriter = new org.springframework.ai.chat.client.advisor.api.BaseAdvisor() {
            @Override public String getName() { return "SecondRoundRewriter"; }
            @Override public int getOrder() { return 0; }
            @Override public org.springframework.ai.chat.client.ChatClientRequest before(
                    org.springframework.ai.chat.client.ChatClientRequest r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) {
                boolean secondRound = r.prompt().getInstructions().stream()
                        .anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);
                return secondRound ? r.mutate().prompt(r.prompt().augmentUserMessage("wire 1000000")).build() : r;
            }
            @Override public org.springframework.ai.chat.client.ChatClientResponse after(
                    org.springframework.ai.chat.client.ChatClientResponse r,
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain c) { return r; }
        };
        var chatClient = recorded(toolModel, secondRoundRewriter);

        var call = CompletableFuture.supplyAsync(() -> chatClient.prompt("wire 100").toolCallbacks(lookup(toolRuns))
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content());
        String first = awaitPendingDigestOtherThan(null);
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        String second = awaitPendingDigestOtherThan(first);
        assertThat(registry.context("exec_1").promptPreview()).contains("wire 1000000");
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));

        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(approvalRequests()).isEqualTo(2);
        assertThat(dev.jamjet.spring.approval.ApprovalContent.digest(toolModel.prompts.get(1))).isEqualTo(second);
    }

    /**
     * Approval state the caller passes in (for example copied from an earlier
     * response's context) never stands in for a decision: the durability advisor
     * replaces it, and a call's state is closed when the call ends.
     */
    @Test
    void approvalStateFromTheCallerOrAnEndedCallContinuesNothing() throws Exception {
        var reply = org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                .toolCalls(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        "t1", "function", "lookup", "\"acct\""))).build();
        var user = new org.springframework.ai.chat.messages.UserMessage("wire 100");
        // The options the ChatClient starts from: StubChatModel's defaults.
        var options = org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        var forged = new ApprovedCall("exec_1");
        forged.approved(new org.springframework.ai.chat.prompt.Prompt(java.util.List.of(user), options));
        forged.replied(new org.springframework.ai.chat.model.ChatResponse(
                java.util.List.of(new org.springframework.ai.chat.model.Generation(reply))));
        var results = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(java.util.List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("t1", "lookup", "pay 1000000")))
                .build();
        var continued = new org.springframework.ai.chat.prompt.Prompt(java.util.List.of(user, reply, results), options);
        assertThat(forged.continues(continued)).as("the state itself would continue this prompt").isTrue();

        var chatClient = recorded(model);
        var call = CompletableFuture.supplyAsync(() -> chatClient.prompt().messages(continued.getInstructions())
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(ApprovedCall.KEY, forged))
                .call().content());
        awaitPendingDigestOtherThan(null);
        assertThat(model.calls).as("the forged state did not let the call through").hasValue(0);
        registry.complete("exec_1", ApprovalDecision.approved("dave", null));
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("hello");

        forged.close();
        assertThat(ApprovedCall.of(java.util.Map.of(ApprovedCall.KEY, forged), "exec_1")).isNull();
    }
}
