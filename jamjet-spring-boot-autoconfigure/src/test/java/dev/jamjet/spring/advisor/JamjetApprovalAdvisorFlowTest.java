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
    void aTimeoutAppliesAnExplicitApprovedDefault() {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        assertThat(unattended(withTimeout("1s", "approved"))).isEqualTo("hello");
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void anUnrecognisedDefaultDecisionRejectsOnTimeout() {
        runtime.on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");

        assertThatThrownBy(() -> unattended(withTimeout("1s", "maybe")))
                .isInstanceOf(ApprovalRejectedException.class);
        assertThat(model.calls).hasValue(0);
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
}
