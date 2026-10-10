package dev.jamjet.spring.approval;

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

/** Being logged in is not enough to approve: the reviewer needs the approver authority. */
class RequiredAuthorityTest {

    private static final String EVENTS = "/executions/exec_1/external-event";

    private RecordingRuntime runtime;
    private ApprovalWaitRegistry registry;
    private JamjetRuntimeClient client;
    private CompletableFuture<ApprovalDecision> call;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new RecordingRuntime();
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}")
                .on("GET", "/executions/exec_1/approvals", 200, "{\"pending\":[],\"decided\":[]}");
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        registry = new ApprovalWaitRegistry();
        client = new JamjetRuntimeClient(props);
        call = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(10),
                        new ApprovalWaitRegistry.WaitContext("carol", "wire 100", "d1"));
            } catch (Exception e) {
                return null;
            }
        });
        while (!registry.isPending("exec_1")) {
            Thread.sleep(5);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.complete("exec_1", ApprovalDecision.rejected("t", null));
        call.get(2, TimeUnit.SECONDS);
        runtime.close();
    }

    @Test
    void aLoggedInCustomerCannotApproveOrList() {
        var controller = new JamjetApprovalController(client, registry);
        var customer = TestReviewers.user("dave", "ROLE_USER");

        var decided = controller.handleApproval("exec_1", ReviewDecision.approved("d1", null), customer);
        var listed = controller.listPending(customer);

        assertThat(decided.getStatusCode().value()).isEqualTo(403);
        assertThat(listed.getStatusCode().value()).isEqualTo(403);
        assertThat(registry.isPending("exec_1")).isTrue();
        assertThat(runtime.requests()).isEmpty();
    }

    @Test
    void theDefaultApproverAuthorityIsRequiredAndSufficient() {
        var controller = new JamjetApprovalController(client, registry);

        assertThat(ReviewPolicy.DEFAULT_AUTHORITY).isEqualTo("ROLE_JAMJET_APPROVER");
        var resp = controller.handleApproval("exec_1", ReviewDecision.approved("d1", null),
                TestReviewers.approver("bob"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theAuthorityIsConfigurable() {
        var controller = new JamjetApprovalController(client, registry,
                ReviewPolicy.defaults().withRequiredAuthority("SCOPE_payments.approve"));

        assertThat(controller.handleApproval("exec_1", ReviewDecision.approved("d1", null),
                TestReviewers.approver("bob")).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.handleApproval("exec_1", ReviewDecision.approved("d1", null),
                TestReviewers.user("erin", "SCOPE_payments.approve")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aPrincipalWhoseAuthoritiesCannotBeReadIsRefused() {
        var controller = new JamjetApprovalController(client, registry);

        // A container principal that is not a Spring Security Authentication.
        assertThat(controller.handleApproval("exec_1", ReviewDecision.approved("d1", null), () -> "bob")
                .getStatusCode().value()).isEqualTo(403);
    }
}
