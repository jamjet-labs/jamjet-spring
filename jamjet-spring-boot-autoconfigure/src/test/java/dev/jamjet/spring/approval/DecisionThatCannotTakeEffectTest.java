package dev.jamjet.spring.approval;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A decision recorded on the runtime that can no longer reach its call must not
 * stand as if it applied: the reviewer is told, and the record is corrected.
 */
class DecisionThatCannotTakeEffectTest {

    private static final String EVENTS = "/executions/exec_1/external-event";
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Test
    void aCallThatStoppedWaitingWhileTheDecisionWasRecordedVoidsTheDecision() throws Exception {
        var outcome = new AtomicReference<Object>();
        var waiter = new Thread(() -> {
            try {
                outcome.set(registry.waitForApproval("exec_1", Duration.ofSeconds(30),
                        new ApprovalWaitRegistry.WaitContext("carol", "wire 100", "d1")));
            } catch (Exception e) {
                outcome.set(e);
            }
        });
        waiter.start();
        while (!registry.isPending("exec_1")) {
            Thread.sleep(5);
        }
        // Recording takes a while; the waiting call is interrupted meanwhile.
        runtime.onSlow("POST", EVENTS, 200, "{\"accepted\":true}", 700);
        var decision = CompletableFuture.supplyAsync(
                () -> controller.handleApproval("exec_1", ReviewDecision.approved("d1", null), () -> "bob"));
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(5000);

        var resp = decision.get(5, TimeUnit.SECONDS);

        assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat((String) resp.getBody().get("error")).contains("did not take effect");
        var keys = runtime.requests().stream().filter(r -> r.path().equals(EVENTS))
                .map(r -> {
                    try {
                        return JSON.readTree(r.body()).get("correlation_key").asText();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).toList();
        assertThat(keys).containsExactly("spring_ai.approval_decision", "spring_ai.approval_decision_void");
    }
}
