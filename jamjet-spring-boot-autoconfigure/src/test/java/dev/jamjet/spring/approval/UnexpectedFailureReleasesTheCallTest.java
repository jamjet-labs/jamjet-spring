package dev.jamjet.spring.approval;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Any failure between claiming a call and releasing it releases it as rejected. */
class UnexpectedFailureReleasesTheCallTest {

    @Test
    void anUncheckedErrorWhileRecordingRejectsTheCallAtOnce() throws Exception {
        var props = new JamjetProperties();
        props.setRuntimeUrl("http://127.0.0.1:9");
        var registry = new ApprovalWaitRegistry();
        var client = new JamjetRuntimeClient(props) {
            @Override
            public void sendExternalEvent(String executionId, String key, Map<String, Object> payload) {
                throw new IllegalStateException("serializer blew up");
            }
        };
        var controller = new JamjetApprovalController(client, registry);
        var call = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(30),
                        new ApprovalWaitRegistry.WaitContext("carol", "x", "d1"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        while (!registry.isPending("exec_1")) {
            Thread.sleep(5);
        }

        var resp = controller.handleApproval("exec_1", ReviewDecision.approved("d1", null),
                TestReviewers.approver("bob"));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        ApprovalDecision released = call.get(2, TimeUnit.SECONDS);
        assertThat(released.isApproved()).isFalse();
        assertThat(released.comment()).contains("serializer blew up");
    }

    @Test
    void anErrorWhileRecordingRejectsTheCallAtOnceAndIsRethrown() throws Exception {
        var props = new JamjetProperties();
        props.setRuntimeUrl("http://127.0.0.1:9");
        var registry = new ApprovalWaitRegistry();
        var client = new JamjetRuntimeClient(props) {
            @Override
            public void sendExternalEvent(String executionId, String key, Map<String, Object> payload) {
                throw new StackOverflowError("deep recursion in a serializer");
            }
        };
        var controller = new JamjetApprovalController(client, registry);
        var call = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.waitForApproval("exec_1", Duration.ofSeconds(30),
                        new ApprovalWaitRegistry.WaitContext("carol", "x", "d1"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        while (!registry.isPending("exec_1")) {
            Thread.sleep(5);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.handleApproval("exec_1",
                        ReviewDecision.approved("d1", null), TestReviewers.approver("bob")))
                .isInstanceOf(StackOverflowError.class);
        assertThat(call.get(2, TimeUnit.SECONDS).isApproved()).isFalse();
    }
}
