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

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetApprovalControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String APPROVALS = "/executions/exec_1/approvals";
    private static final String EVENTS = "/executions/exec_1/external-event";
    private static final Principal BOB = () -> "bob";
    private static final String DIGEST = "c0ffee";

    private static ReviewDecision approved() {
        return new ReviewDecision("approved", null, DIGEST);
    }

    private RecordingRuntime runtime;
    private ApprovalWaitRegistry registry;
    private JamjetRuntimeClient client;
    private JamjetApprovalController controller;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        props.setApiToken("app-service-token");
        client = new JamjetRuntimeClient(props);
        registry = new ApprovalWaitRegistry();
        controller = new JamjetApprovalController(client, registry);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    /** Park a thread on the local wait, as JamjetApprovalAdvisor does. */
    private CompletableFuture<ApprovalDecision> parkedCall(String initiator) throws Exception {
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(30),
                        new ApprovalWaitRegistry.WaitContext(initiator, "send 100 to acct 42", DIGEST));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!registry.isPending("exec_1")) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("waiter never registered");
            }
            Thread.sleep(5);
        }
        return future;
    }

    private JsonNode recordedDecision() throws Exception {
        var r = runtime.requests().stream().filter(x -> x.method().equals("POST") && x.path().equals(EVENTS))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no decision recorded"));
        return JSON.readTree(r.body());
    }

    @Test
    void noAuthenticatedReviewerIsUnauthorizedAndReleasesNothing() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var call = parkedCall(null);

        var resp = controller.handleApproval("exec_1", approved(), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(registry.isPending("exec_1")).isTrue();
        assertThat(runtime.requests()).isEmpty();
        registry.complete("exec_1", ApprovalDecision.rejected("t", "cleanup"));
        call.get(2, TimeUnit.SECONDS);
    }

    @Test
    void theApproverIsThePrincipalNeverTheBody() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var call = parkedCall(null);
        // An older client still sends user_id; it must not be what is recorded.
        var body = JSON.readValue("{\"decision\":\"approved\",\"user_id\":\"alice\",\"comment\":\"ok\",\"content_digest\":\"c0ffee\"}",
                ReviewDecision.class);

        var resp = controller.handleApproval("exec_1", body, BOB);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var payload = recordedDecision().get("payload");
        assertThat(payload.get("approver").asText()).isEqualTo("bob");
        assertThat(payload.get("authenticated").asBoolean()).isTrue();
        assertThat(payload.toString()).doesNotContain("alice");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void whoeverTriggeredTheCallCannotApproveIt() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var call = parkedCall("bob");

        var resp = controller.handleApproval("exec_1", approved(), BOB);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(registry.isPending("exec_1")).isTrue();
        assertThat(runtime.requests()).isEmpty();

        var other = controller.handleApproval("exec_1", approved(), () -> "carol");
        assertThat(other.getStatusCode().value()).isEqualTo(200);
        assertThat(recordedDecision().get("payload").get("initiator").asText()).isEqualTo("bob");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void aRuntimeHeldApprovalIsNeverDecidedWithTheAppsToken() {
        runtime.on("GET", APPROVALS, 200,
                "{\"pending\":[{\"node_id\":\"pay\",\"context\":{\"action_digest\":\"d\"}}],\"decided\":[]}");

        var resp = controller.handleApproval("exec_1", approved(), BOB);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat((String) resp.getBody().get("error")).contains("your own runtime token");
        assertThat(runtime.requests()).noneMatch(r -> r.method().equals("POST"));
    }

    @Test
    void anUnrecordableDecisionIsABadGatewayAndRejectsTheCall() throws Exception {
        runtime.on("POST", EVENTS, 500, "{\"error\":\"backend error: disk full\"}");
        var call = parkedCall(null);

        var resp = controller.handleApproval("exec_1", approved(), BOB);

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody()).containsEntry("error", "backend error: disk full");
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isFalse();
    }

    @Test
    void aRuntimeRefusalOfTheRecordKeepsItsStatus() throws Exception {
        runtime.on("POST", EVENTS, 400, "{\"error\":\"invalid execution id: exec_1\"}");
        var call = parkedCall(null);

        var resp = controller.handleApproval("exec_1", approved(), BOB);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isFalse();
    }

    @Test
    void aDecisionOtherThanApprovedOrRejectedIsABadRequest() {
        var resp = controller.handleApproval("exec_1", new ReviewDecision("yes", null, DIGEST), BOB);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(runtime.requests()).isEmpty();
    }

    @Test
    void unauthenticatedDecisionsAreRecordedAsSuchWhenExplicitlyAllowed() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var open = new JamjetApprovalController(client, registry, true);
        var call = parkedCall(null);

        var resp = open.handleApproval("exec_1", approved(), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var payload = recordedDecision().get("payload");
        assertThat(payload.get("approver").asText()).isEqualTo("unauthenticated");
        assertThat(payload.get("authenticated").asBoolean()).isFalse();
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void thePendingListShowsRuntimeHeldRequestsReadOnlyWithTheCommand() throws Exception {
        runtime.on("GET", APPROVALS, 200, """
                {"pending":[{"node_id":"pay","tool_name":"send_wire","approver":"human","sequence":4,
                  "context":{"action_digest":"d-123"}}],"decided":[]}
                """);
        var call = parkedCall(null);

        var listed = controller.listPending().getBody();

        assertThat(listed).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("execution_id", "exec_1");
            assertThat((String) item.get("runtime_requests_note")).contains("own runtime token");
            @SuppressWarnings("unchecked")
            var requests = (List<Map<String, Object>>) item.get("runtime_requests");
            assertThat(requests).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("action_digest", "d-123");
                assertThat((String) r.get("decide_with"))
                        .isEqualTo("jamjet approve exec_1 --decision approved --node-id pay --action-digest d-123");
            });
        });
        assertThat(runtime.requests()).noneMatch(r -> r.method().equals("POST"));
        registry.complete("exec_1", ApprovalDecision.rejected("t", "cleanup"));
        call.get(2, TimeUnit.SECONDS);
    }

    @Test
    void aDecisionMustEchoTheContentDigestTheReviewerWasShown() throws Exception {
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var call = parkedCall(null);

        var missing = controller.handleApproval("exec_1", new ReviewDecision("approved", null, null), BOB);
        var stale = controller.handleApproval("exec_1", new ReviewDecision("approved", null, "beef"), BOB);

        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(runtime.requests()).isEmpty();
        assertThat(registry.isPending("exec_1")).isTrue();

        var ok = controller.handleApproval("exec_1", approved(), BOB);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(recordedDecision().get("payload").get("content_digest").asText()).isEqualTo(DIGEST);
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void thePendingListShowsWhatIsBeingApproved() throws Exception {
        runtime.on("GET", APPROVALS, 200, "{\"pending\":[],\"decided\":[]}");
        var call = parkedCall(null);

        var item = controller.listPending().getBody().get(0);

        assertThat(item).containsEntry("prompt", "send 100 to acct 42").containsEntry("content_digest", DIGEST);
        registry.complete("exec_1", ApprovalDecision.rejected("t", "cleanup"));
        call.get(2, TimeUnit.SECONDS);
    }
}
