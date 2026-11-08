package dev.jamjet.spring.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.JamjetProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The work-item side of a recorded call, against a runtime that answers like the real one. */
class RecordedCallsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUEUE = "spring-ai-chat.calls";

    private RecordingRuntime runtime;
    private RecordedCalls calls;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        calls = new RecordedCalls(new JamjetRuntimeClient(props),
                RecordedCalls.Spec.call("spring-ai-chat", "chat", "spring_ai.completed", "Spring AI ChatClient call",
                        "test"),
                RecordedCalls.DEFAULT_LEASE, Duration.ofMinutes(5), Duration.ofMillis(400)).manualUpkeep();
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"2.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}");
    }

    @AfterEach
    void tearDown() {
        calls.close();
        runtime.close();
    }

    private static String item(String id, String executionId, long fence) {
        return """
                {"claimed":true,"work_item":{"id":"%s","execution_id":"%s","node_id":"chat","queue_type":"%s",
                 "payload":{"handler":"record_call","input":{}},"attempt":0,"lease_fence":%d,"lease_secs":60,
                 "idempotency_key":"key-%s"}}""".formatted(id, executionId, QUEUE, fence, id);
    }

    /** Answer successive claims with {@code answers}, then with nothing. */
    private void claimsAnswer(String... answers) {
        var n = new AtomicInteger();
        runtime.on("POST", "/work-items/claim", 200, answers.length > 0 ? answers[0] : "{\"claimed\":false}");
        runtime.whenReceived("POST", "/work-items/claim", () -> {
            int i = n.getAndIncrement();
            runtime.on("POST", "/work-items/claim", 200, i < answers.length ? answers[i] : "{\"claimed\":false}");
        });
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

    private static JsonNode body(RecordingRuntime.Request r) throws Exception {
        return JSON.readTree(r.body());
    }

    @Test
    void claimsTheNodeWhenTheCallStartsAndCompletesItWithTheOutcome() throws Exception {
        claimsAnswer(item("wi-1", "exec_1", 7));
        runtime.on("POST", "/work-items/wi-1/complete", 200, "{\"completed\":true}");

        assertThat(calls.start(Map.of("session_id", "s"))).isEqualTo("exec_1");
        assertThat(calls.heldExecutions()).containsExactly("exec_1");

        JsonNode claim = body(posts("/work-items/claim").get(0));
        assertThat(claim.get("worker_id").asText()).startsWith("jamjet-spring-");
        assertThat(claim.get("queue_types")).hasSize(1);
        assertThat(claim.get("queue_types").get(0).asText()).isEqualTo(QUEUE);
        assertThat(claim.get("lease_secs").asLong()).isEqualTo(60);

        calls.finish("exec_1", Map.of("status", "completed", "model", "m"));

        JsonNode complete = body(posts("/work-items/wi-1/complete").get(0));
        assertThat(complete.get("output")).isEqualTo(JSON.readTree("{\"status\":\"completed\",\"model\":\"m\"}"));
        assertThat(complete.get("state_patch")).isEqualTo(JSON.readTree("{}"));
        assertThat(complete.get("lease_fence").asLong()).isEqualTo(7);
        assertThat(complete.get("idempotency_key").asText()).isEqualTo("key-wi-1");
        assertThat(complete.get("duration_ms").asLong()).isNotNegative();
        assertThat(calls.heldExecutions()).isEmpty();
    }

    @Test
    void aFailedCallFailsItsNode() throws Exception {
        claimsAnswer(item("wi-1", "exec_1", 7));
        runtime.on("POST", "/work-items/wi-1/fail", 200, "{\"failed\":true,\"retryable\":false}");

        calls.start(Map.of());
        calls.finish("exec_1", Map.of("status", "failed", "error", "IllegalStateException: boom"));

        assertThat(posts("/work-items/wi-1/complete")).isEmpty();
        assertThat(body(posts("/work-items/wi-1/fail").get(0)))
                .isEqualTo(JSON.readTree("{\"error\":\"IllegalStateException: boom\",\"lease_fence\":7}"));
    }

    @Test
    void theLeaseIsRenewedWhileTheCallRunsAndTheRenewedFenceIsEchoed() throws Exception {
        claimsAnswer(item("wi-1", "exec_1", 7));
        runtime.on("POST", "/work-items/wi-1/heartbeat", 200,
                "{\"renewed\":true,\"lease_fence\":9,\"reattached\":false,\"cancel_requested\":false}");
        runtime.on("POST", "/work-items/wi-1/complete", 200, "{\"completed\":true}");
        calls.start(Map.of());

        calls.sweep(Instant.now().plusSeconds(5));
        assertThat(posts("/work-items/wi-1/heartbeat")).as("not due yet").isEmpty();
        calls.sweep(Instant.now().plusSeconds(21));
        JsonNode beat = body(posts("/work-items/wi-1/heartbeat").get(0));
        assertThat(beat.get("lease_fence").asLong()).isEqualTo(7);
        assertThat(beat.get("worker_id").asText())
                .isEqualTo(body(posts("/work-items/claim").get(0)).get("worker_id").asText());

        calls.finish("exec_1", Map.of("status", "completed"));
        assertThat(body(posts("/work-items/wi-1/complete").get(0)).get("lease_fence").asLong()).isEqualTo(9);
    }

    @Test
    void aClaimThatTakesAnotherCallsNodeHoldsItUntilThatCallReportsItsOutcome() throws Exception {
        // Another instance started a call just before this one; the claim hands
        // out the oldest node first.
        claimsAnswer(item("wi-other", "exec_other", 3), item("wi-1", "exec_1", 4));
        runtime.on("POST", "/work-items/wi-1/complete", 200, "{\"completed\":true}")
                .on("POST", "/work-items/wi-other/complete", 200, "{\"completed\":true}")
                .on("GET", "/executions/exec_other/events", 200, "{\"events\":[]}");

        runtime.on("POST", "/executions/exec_other/external-event", 200, "{\"accepted\":true}");

        calls.start(Map.of());
        assertThat(calls.heldExecutions()).containsExactlyInAnyOrder("exec_1", "exec_other");
        // The other call's owner learns its node was handed out.
        JsonNode marker = body(posts("/executions/exec_other/external-event").get(0));
        assertThat(marker.get("correlation_key").asText()).isEqualTo(RecordedCalls.HELD_EVENT);
        assertThat(marker.at("/payload/work_item_id").asText()).isEqualTo("wi-other");
        calls.finish("exec_1", Map.of("status", "completed"));
        assertThat(posts("/work-items/wi-1/complete")).hasSize(1);

        // No outcome yet: the node stays held.
        calls.sweep(Instant.now().plusSeconds(3));
        assertThat(posts("/work-items/wi-other/complete")).isEmpty();
        assertThat(calls.heldExecutions()).containsExactly("exec_other");

        // The other instance reports its outcome: the node is completed with it.
        runtime.on("GET", "/executions/exec_other/events", 200, """
                {"events":[{"id":"e1","execution_id":"exec_other","sequence":3,"kind":{"type":"external_event_received",
                "correlation_key":"spring_ai.completed","payload":{"status":"completed","model":"other"}}}]}""");
        calls.sweep(Instant.now().plusSeconds(6));
        JsonNode complete = body(posts("/work-items/wi-other/complete").get(0));
        assertThat(complete.get("output")).isEqualTo(JSON.readTree("{\"status\":\"completed\",\"model\":\"other\"}"));
        assertThat(complete.get("lease_fence").asLong()).isEqualTo(3);
        assertThat(calls.heldExecutions()).isEmpty();
    }

    @Test
    void aLeaseThatWasNotRenewedInTimeIsDropped() {
        claimsAnswer(item("wi-1", "exec_1", 4));
        calls.start(Map.of());

        // Past the lease with no renewal (the runtime was unreachable): the runtime
        // has failed the node by now, so nothing more is sent for it.
        calls.sweep(Instant.now().plus(Duration.ofMinutes(2)));
        assertThat(calls.heldExecutions()).isEmpty();
        calls.finish("exec_1", Map.of("status", "completed"));
        assertThat(runtime.requests()).noneMatch(r -> r.path().startsWith("/work-items/wi-1"));
    }

    @Test
    void aHeldNodeWhoseCallNeverReportsIsFailedAfterTheTimeout() throws Exception {
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        // A lease longer than the timeout, so the timeout is what ends it.
        try (var shortWait = new RecordedCalls(new JamjetRuntimeClient(props),
                RecordedCalls.Spec.call("spring-ai-chat", "chat", "spring_ai.completed", "n", "test"),
                Duration.ofMinutes(10), Duration.ofSeconds(30)).manualUpkeep()) {
            claimsAnswer(item("wi-other", "exec_other", 3), item("wi-1", "exec_1", 4));
            runtime.on("GET", "/executions/exec_other/events", 200, "{\"events\":[]}")
                    .on("POST", "/work-items/wi-other/heartbeat", 200,
                            "{\"renewed\":true,\"lease_fence\":3,\"reattached\":false,\"cancel_requested\":false}")
                    .on("POST", "/work-items/wi-other/fail", 200, "{\"failed\":true}");
            shortWait.start(Map.of());

            shortWait.sweep(Instant.now().plusSeconds(10));
            assertThat(posts("/work-items/wi-other/fail")).isEmpty();
            shortWait.sweep(Instant.now().plusSeconds(40));
            assertThat(body(posts("/work-items/wi-other/fail").get(0)).get("error").asText())
                    .contains("no outcome was reported for this call");
        }
    }

    private void eventsOfExec1(String... kinds) {
        var events = new StringBuilder("{\"events\":[");
        for (int i = 0; i < kinds.length; i++) {
            events.append(i == 0 ? "" : ",").append("{\"id\":\"e").append(i).append("\",\"execution_id\":\"exec_1\",")
                    .append("\"sequence\":").append(i + 1).append(",\"kind\":").append(kinds[i]).append('}');
        }
        runtime.on("GET", "/executions/exec_1/events", 200, events.append("]}").toString());
    }

    @Test
    void aNodeTheRuntimeHoldsForApprovalRefusesTheCallAndCancelsIt() {
        claimsAnswer();
        eventsOfExec1("{\"type\":\"workflow_started\"}",
                "{\"type\":\"tool_approval_required\",\"node_id\":\"chat\",\"tool_name\":\"spring-ai-chat.calls/record_call\","
                        + "\"approver\":\"human\",\"context\":{\"action_digest\":\"d1\"}}");
        runtime.on("POST", "/executions/exec_1/cancel", 200, "{\"cancelled\":true}");

        assertThatThrownBy(() -> calls.start(Map.of()))
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("holds node chat of execution exec_1 for approval");
        assertThat(posts("/executions/exec_1/cancel")).hasSize(1);
        assertThat(calls.heldExecutions()).isEmpty();
    }

    @Test
    void aNodeThePolicyBlockedRefusesTheCallWithTheRuntimesReason() {
        // The block is in the log as soon as the claim answers; the execution's
        // status only changes on the scheduler's next tick.
        claimsAnswer();
        eventsOfExec1("{\"type\":\"policy_violation\",\"node_id\":\"chat\",\"rule\":\"block_tool:spring-ai-chat.*\","
                        + "\"decision\":\"blocked\",\"policy_scope\":\"tenant\"}",
                "{\"type\":\"node_failed\",\"node_id\":\"chat\",\"error\":\"policy blocked: tool is blocked\","
                        + "\"attempt\":0,\"retryable\":false}");
        runtime.on("POST", "/executions/exec_1/cancel", 200, "{\"cancelled\":true}");

        assertThatThrownBy(() -> calls.start(Map.of()))
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("ended execution exec_1 before the call ran")
                .hasMessageContaining("policy blocked");
    }

    @Test
    void whenAnotherInstanceHoldsTheNodeTheCallGoesAhead() {
        claimsAnswer();
        eventsOfExec1("{\"type\":\"external_event_received\",\"correlation_key\":\"" + RecordedCalls.HELD_EVENT
                + "\",\"payload\":{\"worker_id\":\"other\"}}");

        assertThat(calls.start(Map.of())).isEqualTo("exec_1");
        calls.finish("exec_1", Map.of("status", "completed"));
        assertThat(runtime.requests()).noneMatch(r -> r.path().contains("/complete") || r.path().contains("/fail"));
    }

    @Test
    void aNodeNobodyAccountsForFailsTheStartAndCancelsTheExecution() {
        // Withheld with no decision recorded (the runtime could not evaluate its
        // policy), or claimed by an instance that could not say so.
        claimsAnswer();
        eventsOfExec1("{\"type\":\"workflow_started\"}");
        runtime.on("POST", "/executions/exec_1/cancel", 200, "{\"cancelled\":true}");

        assertThatThrownBy(() -> calls.start(Map.of()))
                .isInstanceOfSatisfying(JamjetClientException.class, e -> assertThat(e.getMessage())
                        .contains("did not hand out the node of execution exec_1"));
        assertThat(posts("/executions/exec_1/cancel")).hasSize(1);
        assertThat(posts("/work-items/claim").size()).as("kept claiming while it waited").isGreaterThan(1);
    }

    @Test
    void aClaimTheRuntimeCannotAnswerFailsTheStart() {
        // No route for the claim: the runtime's web UI answers, as it does for a
        // path it does not serve.
        runtime.on("POST", "/executions/exec_1/cancel", 200, "{\"cancelled\":true}");

        assertThatThrownBy(() -> calls.start(Map.of())).isInstanceOf(JamjetClientException.class);
        assertThat(posts("/executions/exec_1/cancel")).hasSize(1);
        assertThat(calls.heldExecutions()).isEmpty();
    }

    @Test
    void aCancelledCallCancelsItsExecution() {
        claimsAnswer(item("wi-1", "exec_1", 7));
        runtime.on("POST", "/executions/exec_1/cancel", 200, "{\"cancelled\":true}");
        calls.start(Map.of());

        calls.finish("exec_1", Map.of("status", "cancelled", "streamed", true));
        assertThat(posts("/executions/exec_1/cancel")).hasSize(1);
        assertThat(runtime.requests()).noneMatch(r -> r.path().startsWith("/work-items/wi-1"));
    }

    @Test
    void aRuntimeWithoutExternalFnGetsTheWaitWorkflowAndNothingIsClaimed() throws Exception {
        var registrations = new AtomicInteger();
        runtime.whenReceived("POST", "/workflows", () -> {
            if (registrations.incrementAndGet() == 1) {
                runtime.on("POST", "/workflows", 400, "{\"error\":\"invalid workflow IR: unknown variant "
                        + "`external_fn`, expected one of `model`, `tool`, `python_fn`, `wait`\"}");
            } else {
                runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}");
            }
        });

        assertThat(calls.start(Map.of())).isEqualTo("exec_1");
        calls.finish("exec_1", Map.of("status", "completed"));

        var workflows = posts("/workflows");
        assertThat(workflows).hasSize(2);
        assertThat(body(workflows.get(0)).at("/ir/version").asText()).isEqualTo("2.0.0");
        assertThat(body(workflows.get(1)).at("/ir/version").asText()).isEqualTo("1.0.0");
        assertThat(body(workflows.get(1)).at("/ir/nodes/chat/kind/type").asText()).isEqualTo("wait");
        assertThat(body(posts("/executions").get(0)).get("workflow_version").asText()).isEqualTo("1.0.0");
        assertThat(runtime.requests()).noneMatch(r -> r.path().startsWith("/work-items"));
        assertThat(calls.registeredVersion()).isEqualTo("1.0.0");
    }

    @Test
    void anyOtherRegistrationRefusalIsNotMistakenForAnOldRuntime() {
        runtime.on("POST", "/workflows", 400, "{\"error\":\"invalid workflow IR: this runtime cannot execute "
                + "node(s) 'chat' (external_fn)\"}");

        assertThatThrownBy(() -> calls.start(Map.of()))
                .isInstanceOfSatisfying(JamjetClientException.class, e -> assertThat(e.getStatusCode()).isEqualTo(400));
        assertThat(posts("/workflows")).hasSize(1);
    }

    @Test
    void theQueueIsTheWorkflowIdOrAValidNameDerivedFromIt() {
        assertThat(RecordedCallWorkflow.queueFor("spring-ai-chat")).isEqualTo("spring-ai-chat.calls");
        for (String odd : List.of("billing chat", "a/b", "x".repeat(70), "ünïcode")) {
            String queue = RecordedCallWorkflow.queueFor(odd);
            assertThat(queue).startsWith("recorded-calls.").hasSizeLessThanOrEqualTo(64)
                    .matches("[A-Za-z0-9._-]+");
            assertThat(RecordedCallWorkflow.queueFor(odd)).as("stable").isEqualTo(queue);
        }
        assertThat(RecordedCallWorkflow.queueFor("a b")).isNotEqualTo(RecordedCallWorkflow.queueFor("a_b"));
    }
}
