package dev.jamjet.spring.approval;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import dev.jamjet.spring.advisor.StubChatModel;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A decision claims the waiting call before anything is recorded. */
class ApprovalDecisionRaceTest {

    private static final String EVENTS = "/executions/exec_1/external-event";

    private RecordingRuntime runtime;
    private JamjetProperties props;
    private ApprovalWaitRegistry registry;
    private JamjetApprovalController controller;
    private StubChatModel model;
    private ChatClient chat;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        registry = new ApprovalWaitRegistry();
        var client = new JamjetRuntimeClient(props);
        // These calls run with no SecurityContext, so their initiator is unknown;
        // this test is about the claim, not separation of duties.
        controller = new JamjetApprovalController(client, registry,
                ReviewPolicy.defaults().withAllowUnknownInitiator(true));
        model = StubChatModel.answering("wire sent");
        chat = ChatClient.builder(model)
                .defaultAdvisors(new JamjetApprovalAdvisor(client, props, registry)).build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private CompletableFuture<String> pausedCall() throws Exception {
        var call = CompletableFuture.supplyAsync(() -> chat.prompt("wire 100")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                        .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"))
                .call().content());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!registry.isPending("exec_1")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        return call;
    }

    private long decisionsRecorded() {
        return runtime.requests().stream()
                .filter(r -> r.path().equals(EVENTS) && r.body().contains("spring_ai.approval_decision")).count();
    }

    @Test
    void aDecisionInFlightWhenTheTimeoutFiresIsTheOneThatApplies() throws Exception {
        props.getApproval().setTimeout("1s");
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var call = pausedCall();
        // Recording the decision takes longer than what is left of the timeout.
        runtime.onSlow("POST", EVENTS, 200, "{\"accepted\":true}", 2000);

        var resp = controller.handleApproval("exec_1", ReviewDecision.approved(registry.context("exec_1").contentDigest(), null), TestReviewers.approver("bob"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // What the runtime recorded and what the call did must agree.
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("wire sent");
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void ofTwoConcurrentDecisionsOneWinsAndTheOtherIsAConflictThatRecordsNothing() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var call = pausedCall();
        runtime.onSlow("POST", EVENTS, 200, "{\"accepted\":true}", 500);

        String digest = registry.context("exec_1").contentDigest();
        var approve = CompletableFuture.supplyAsync(
                () -> controller.handleApproval("exec_1", ReviewDecision.approved(digest, null), TestReviewers.approver("bob")));
        var reject = CompletableFuture.supplyAsync(
                () -> controller.handleApproval("exec_1", ReviewDecision.rejected(digest, null), TestReviewers.approver("carol")));

        var statuses = List.of(approve.get(5, TimeUnit.SECONDS).getStatusCode().value(),
                reject.get(5, TimeUnit.SECONDS).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(decisionsRecorded()).isEqualTo(1);
        try {
            call.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // whichever won; the call is settled either way
        }
    }
}
