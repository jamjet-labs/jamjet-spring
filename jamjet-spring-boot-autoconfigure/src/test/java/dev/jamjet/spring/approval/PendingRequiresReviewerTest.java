package dev.jamjet.spring.approval;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /pending shows prompts and initiators: it needs a reviewer too. Ported from probe P6. */
class PendingRequiresReviewerTest {

    private RecordingRuntime runtime;
    private ApprovalWaitRegistry registry;
    private JamjetApprovalController controller;
    private CompletableFuture<ApprovalDecision> call;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new RecordingRuntime();
        runtime.on("GET", "/executions/exec_1/approvals", 200, "{\"pending\":[],\"decided\":[]}");
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        registry = new ApprovalWaitRegistry();
        controller = new JamjetApprovalController(new JamjetRuntimeClient(props), registry);
        call = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(10),
                        new ApprovalWaitRegistry.WaitContext("carol", "transfer 5000 to IBAN DE89", "d1"));
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
    void withoutAPrincipalNothingIsListed() {
        var resp = controller.listPending(null);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(String.valueOf(resp.getBody())).doesNotContain("IBAN", "carol");
    }

    @Test
    void anAnonymousPrincipalSeesNothing() {
        var anonymous = new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(controller.listPending(anonymous).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aReviewerSeesTheWaitingCalls() {
        var bob = new UsernamePasswordAuthenticationToken("bob", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_JAMJET_APPROVER"));

        var resp = controller.listPending(bob);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var listed = (List<Map<String, Object>>) resp.getBody();
        assertThat(listed).singleElement().satisfies(i -> assertThat(i).containsEntry("execution_id", "exec_1"));
    }
}
