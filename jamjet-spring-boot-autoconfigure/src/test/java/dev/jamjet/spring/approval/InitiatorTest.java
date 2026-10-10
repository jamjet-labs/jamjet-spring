package dev.jamjet.spring.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import dev.jamjet.spring.advisor.JamjetApprovalGuard;
import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import dev.jamjet.spring.advisor.StubChatModel;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordingRuntime;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who triggered a paused call must be known before any thread hop, so
 * separation of duties holds on every path; when it is not known, approval is
 * refused. Ported from the re-review's probe P1.
 */
class InitiatorTest {

    private static final String EVENTS = "/executions/exec_1/external-event";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingRuntime runtime;
    private JamjetProperties props;
    private ApprovalWaitRegistry registry;
    private JamjetRuntimeClient client;
    private JamjetApprovalController controller;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        runtime.on("POST", EVENTS, 200, "{\"accepted\":true}");
        props = new JamjetProperties();
        props.setRuntimeUrl(runtime.url());
        props.setAllowStreaming(true);
        props.getApproval().setTimeout("10s");
        registry = new ApprovalWaitRegistry();
        client = new JamjetRuntimeClient(props);
        controller = new JamjetApprovalController(client, registry);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        SecurityContextHolder.clearContext();
    }

    private static <T> T as(String user, Supplier<T> body) {
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", List.of()));
        SecurityContextHolder.setContext(ctx);
        try {
            return body.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void awaitPending() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!registry.isPending("exec_1")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private int approveAs(String user) {
        return controller.handleApproval("exec_1",
                ReviewDecision.approved(registry.context("exec_1").contentDigest(), null),
                TestReviewers.approver(user)).getStatusCode().value();
    }

    private ChatClient.ChatClientRequestSpec marked(ChatClient chat) {
        return chat.prompt("wire 100").advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true)
                .param(JamjetDurabilityAdvisor.EXECUTION_ID_KEY, "exec_1"));
    }

    @Test
    void onAStreamTheCallersPrincipalIsStillTheInitiator() throws Exception {
        var model = StubChatModel.answering("wire sent");
        var chat = ChatClient.builder(model)
                .defaultAdvisors(new JamjetApprovalAdvisor(client, props, registry)).build();

        var call = CompletableFuture.supplyAsync(() -> as("carol",
                () -> String.join("", marked(chat).stream().content().collectList().block())));
        awaitPending();

        assertThat(registry.context("exec_1").initiator()).isEqualTo("carol");
        assertThat(approveAs("carol")).as("carol cannot approve her own call").isEqualTo(403);
        assertThat(approveAs("dave")).isEqualTo(200);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("wire sent");
    }

    @Test
    void withTheGuardInFrontAStreamKeepsTheInitiatorAcrossItsThreadHop() throws Exception {
        var model = StubChatModel.answering("wire sent");
        var chat = ChatClient.builder(model).defaultAdvisors(new JamjetApprovalGuard(),
                new JamjetDurabilityAdvisor(client, props), new JamjetApprovalAdvisor(client, props, registry))
                .build();
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}");

        var call = CompletableFuture.supplyAsync(() -> as("carol",
                () -> String.join("", chat.prompt("wire 100")
                        .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                        .stream().content().collectList().block())));
        awaitPending();

        assertThat(registry.context("exec_1").initiator()).isEqualTo("carol");
        assertThat(approveAs("dave")).isEqualTo(200);
        call.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aReactiveCallersPrincipalIsTheInitiator() throws Exception {
        var model = StubChatModel.answering("wire sent");
        var chat = ChatClient.builder(model).defaultAdvisors(new JamjetApprovalGuard(),
                new JamjetApprovalAdvisor(client, props, registry)).build();
        var auth = new UsernamePasswordAuthenticationToken("carol", "n/a", List.of());

        var call = CompletableFuture.supplyAsync(() -> String.join("", marked(chat).stream().content()
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(auth))))
                .collectList().block()));
        awaitPending();

        assertThat(registry.context("exec_1").initiator()).isEqualTo("carol");
        assertThat(approveAs("carol")).isEqualTo(403);
        assertThat(approveAs("dave")).isEqualTo(200);
        call.get(5, TimeUnit.SECONDS);
    }

    @Test
    void anUnknownInitiatorIsRecordedAndCannotBeApprovedByDefault() throws Exception {
        var model = StubChatModel.answering("wire sent");
        var chat = ChatClient.builder(model)
                .defaultAdvisors(new JamjetApprovalAdvisor(client, props, registry)).build();

        var call = CompletableFuture.supplyAsync(() -> marked(chat).call().content());
        awaitPending();

        var requested = runtime.requests().stream().filter(r -> r.body().contains("spring_ai.approval_requested"))
                .findFirst().orElseThrow();
        assertThat(JSON.readTree(requested.body()).get("payload").get("initiator_known").asBoolean()).isFalse();
        assertThat(approveAs("dave")).as("separation of duties cannot be checked").isEqualTo(403);
        assertThat(registry.isPending("exec_1")).isTrue();

        var reject = controller.handleApproval("exec_1",
                ReviewDecision.rejected(registry.context("exec_1").contentDigest(), "who sent this?"),
                TestReviewers.approver("dave"));
        assertThat(reject.getStatusCode().value()).as("rejecting is always allowed").isEqualTo(200);
        assertThat(model.calls).hasValue(0);
        try {
            call.get(5, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // rejected
        }
    }

    @Test
    void anUnknownInitiatorCanBeApprovedWhenExplicitlyAllowed() throws Exception {
        var lenient = new JamjetApprovalController(client, registry,
                ReviewPolicy.defaults().withAllowUnknownInitiator(true));
        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(10),
                        new ApprovalWaitRegistry.WaitContext(null, "x", "d1"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitPending();

        var resp = lenient.handleApproval("exec_1", ReviewDecision.approved("d1", null), TestReviewers.approver("dave"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var decision = runtime.requests().stream().filter(r -> r.body().contains("spring_ai.approval_decision"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(JSON.readTree(decision.body()).get("payload").get("initiator_known").asBoolean()).isFalse();
        assertThat(waiting.get(5, TimeUnit.SECONDS).isApproved()).isTrue();
    }
}
