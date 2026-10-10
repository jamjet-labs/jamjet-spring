package dev.jamjet.spring.approval;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.security.Principal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Which principals count as a reviewer. Ported from the round-2 re-review probe P5. */
class ReviewerPrincipalTest {

    private static final String EVENTS = "/executions/exec_1/external-event";

    private RecordingRuntime runtime;
    private ApprovalWaitRegistry registry;
    private JamjetRuntimeClient client;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        registry = new ApprovalWaitRegistry();
        client = new JamjetRuntimeClient(props);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private CompletableFuture<ApprovalDecision> parked() throws Exception {
        var f = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(10),
                        new ApprovalWaitRegistry.WaitContext("carol", "x", "d1"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        while (!registry.isPending("exec_1")) {
            Thread.sleep(5);
        }
        return f;
    }

    private int decide(JamjetApprovalController controller, Principal p) {
        return controller.handleApproval("exec_1", ReviewDecision.approved("d1", null), p).getStatusCode().value();
    }

    private static Principal approver(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a",
                AuthorityUtils.createAuthorityList("ROLE_JAMJET_APPROVER"));
    }

    @Test
    void anAnonymousAuthenticationIsNotAReviewer() throws Exception {
        var call = parked();
        var anonymous = new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS", "ROLE_JAMJET_APPROVER"));

        assertThat(decide(new JamjetApprovalController(client, registry), anonymous)).isEqualTo(401);
        assertThat(registry.isPending("exec_1")).isTrue();
        assertThat(runtime.requests()).isEmpty();
        registry.complete("exec_1", ApprovalDecision.rejected("t", null));
        call.get(2, TimeUnit.SECONDS);
    }

    @Test
    void anUnauthenticatedTokenIsNotAReviewer() throws Exception {
        var call = parked();
        var notAuthenticated = new UsernamePasswordAuthenticationToken("mallory", "pw");

        assertThat(decide(new JamjetApprovalController(client, registry), notAuthenticated)).isEqualTo(401);
        assertThat(registry.isPending("exec_1")).isTrue();
        registry.complete("exec_1", ApprovalDecision.rejected("t", null));
        call.get(2, TimeUnit.SECONDS);
    }

    @Test
    void aRememberMeLoginIsNotAReviewerByDefault() throws Exception {
        var call = parked();
        var rememberMe = new RememberMeAuthenticationToken("k", "mallory",
                AuthorityUtils.createAuthorityList("ROLE_JAMJET_APPROVER"));

        assertThat(decide(new JamjetApprovalController(client, registry), rememberMe)).isEqualTo(401);
        assertThat(registry.isPending("exec_1")).isTrue();

        var lenient = new JamjetApprovalController(client, registry,
                ReviewPolicy.defaults().withAllowRememberMe(true));
        assertThat(decide(lenient, rememberMe)).isEqualTo(200);
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void aFullyAuthenticatedReviewerIsAccepted() throws Exception {
        var call = parked();

        assertThat(decide(new JamjetApprovalController(client, registry), approver("bob"))).isEqualTo(200);
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isTrue();
    }
}
