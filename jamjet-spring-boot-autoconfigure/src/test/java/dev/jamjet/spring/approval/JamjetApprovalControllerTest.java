package dev.jamjet.spring.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetApprovalControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String APPROVALS = "/executions/exec_1/approvals";
    private static final String APPROVE = "/executions/exec_1/approve";
    private static final String EVENTS = "/executions/exec_1/external-event";

    private static final String RUNTIME_PENDING = """
            {"pending":[{"node_id":"pay","tool_name":"send_wire","approver":"human","sequence":4,
              "context":{"action_digest":"d-123"}}],"decided":[]}
            """;
    private static final String NOTHING_PENDING = "{\"pending\":[],\"decided\":[]}";

    private RecordingRuntime runtime;
    private ApprovalWaitRegistry registry;
    private JamjetApprovalController controller;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        registry = new ApprovalWaitRegistry();
        controller = new JamjetApprovalController(new JamjetRuntimeClient(props), registry);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    /** Park a thread on the local wait, as JamjetApprovalAdvisor does, and wait until it is parked. */
    private CompletableFuture<ApprovalDecision> parkedCall() throws Exception {
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(30));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.allPending().stream().noneMatch(p -> p.executionId().equals("exec_1"))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("waiter never registered");
            }
            Thread.sleep(5);
        }
        return future;
    }

    private RecordingRuntime.Request lastPost(String path) {
        return runtime.requests().stream()
                .filter(r -> r.method().equals("POST") && r.path().equals(path))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no POST " + path));
    }

    @Test
    void aRuntimeRefusalComesBackWithItsStatusAndMessageAndReleasesTheCall() throws Exception {
        runtime.on("GET", APPROVALS, 200, RUNTIME_PENDING)
                .on("POST", APPROVE, 409, "{\"error\":\"action_digest does not match the pending request\"}");
        var call = parkedCall();

        var resp = controller.handleApproval("exec_1",
                new ApprovalDecision("approved", "pay", "alice", null, "stale"));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).containsEntry("error", "action_digest does not match the pending request");
        // The paused call is released at once, as a rejection, not left to time out.
        var released = call.get(2, TimeUnit.SECONDS);
        assertThat(released.isApproved()).isFalse();
        assertThat(released.comment()).contains("action_digest does not match");
    }

    @Test
    void anUnreachableRuntimeIsABadGatewayAndStillReleasesTheCall() throws Exception {
        runtime.on("GET", APPROVALS, 503, "{\"error\":\"backend error: locked\"}");
        var call = parkedCall();

        var resp = controller.handleApproval("exec_1", ApprovalDecision.approved("alice", null));

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody()).containsEntry("error", "backend error: locked");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isFalse();
    }

    @Test
    void forwardsTheReviewedDigestAndNodeToTheRuntime() throws Exception {
        runtime.on("GET", APPROVALS, 200, RUNTIME_PENDING)
                .on("POST", APPROVE, 200, "{\"execution_id\":\"exec_1\",\"node_id\":\"pay\",\"accepted\":true}");
        var call = parkedCall();

        var resp = controller.handleApproval("exec_1",
                new ApprovalDecision("approved", "pay", "alice", "ok", "d-123"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("accepted", true).containsEntry("node_id", "pay");
        JsonNode sent = JSON.readTree(lastPost(APPROVE).body());
        assertThat(sent.get("action_digest").asText()).isEqualTo("d-123");
        assertThat(sent.get("node_id").asText()).isEqualTo("pay");
        assertThat(sent.get("decision").asText()).isEqualTo("approved");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void anApplicationSideApprovalIsRecordedOnTheExecutionBeforeTheCallResumes() throws Exception {
        runtime.on("GET", APPROVALS, 200, NOTHING_PENDING)
                .on("POST", EVENTS, 200, "{\"execution_id\":\"exec_1\",\"accepted\":true}");
        var call = parkedCall();

        var resp = controller.handleApproval("exec_1", ApprovalDecision.approved("alice", "fine"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode sent = JSON.readTree(lastPost(EVENTS).body());
        assertThat(sent.get("correlation_key").asText()).isEqualTo("spring_ai.approval_decision");
        assertThat(sent.get("payload").get("decision").asText()).isEqualTo("approved");
        assertThat(sent.get("payload").get("user_id").asText()).isEqualTo("alice");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void anApprovalThatCannotBeRecordedIsRefusedAndTheCallRejected() throws Exception {
        runtime.on("GET", APPROVALS, 200, NOTHING_PENDING)
                .on("POST", EVENTS, 500, "{\"error\":\"backend error: disk full\"}");
        var call = parkedCall();

        var resp = controller.handleApproval("exec_1", ApprovalDecision.approved("alice", null));

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody()).containsEntry("error", "backend error: disk full");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isFalse();
    }

    @Test
    void nothingPendingAnywhereIsNotFound() {
        runtime.on("GET", APPROVALS, 200, NOTHING_PENDING);

        var resp = controller.handleApproval("exec_1", ApprovalDecision.approved("alice", null));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(runtime.requests()).noneMatch(r -> r.method().equals("POST"));
    }

    @Test
    void aDecisionOtherThanApprovedOrRejectedIsABadRequest() {
        var resp = controller.handleApproval("exec_1", new ApprovalDecision("yes", null, "a", null, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(runtime.requests()).isEmpty();
    }

    @Test
    void thePendingListShowsTheDigestTheReviewerMustEcho() throws Exception {
        runtime.on("GET", APPROVALS, 200, RUNTIME_PENDING);
        var call = parkedCall();

        var listed = controller.listPending().getBody();

        assertThat(listed).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("execution_id", "exec_1");
            @SuppressWarnings("unchecked")
            var requests = (java.util.List<java.util.Map<String, Object>>) item.get("runtime_requests");
            assertThat(requests).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("node_id", "pay");
                assertThat(r).containsEntry("action_digest", "d-123");
            });
        });
        registry.complete("exec_1", ApprovalDecision.rejected("t", "cleanup"));
        call.get(2, TimeUnit.SECONDS);
    }
}
