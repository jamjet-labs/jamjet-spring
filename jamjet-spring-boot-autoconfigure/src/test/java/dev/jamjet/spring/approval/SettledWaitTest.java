package dev.jamjet.spring.approval;

import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wait that gives up is settled before anything else can happen, so a
 * decision arriving at that instant cannot be delivered to a call that is being
 * rejected. The test hook fires exactly in that window, making the round-3
 * probe P3's race deterministic.
 */
class SettledWaitTest {

    private final ApprovalWaitRegistry registry = new ApprovalWaitRegistry();
    private final AtomicReference<Boolean> lateComplete = new AtomicReference<>();
    private final AtomicReference<Boolean> lateClaim = new AtomicReference<>();
    private final AtomicReference<Object> outcome = new AtomicReference<>();

    private Thread waiting(Duration timeout, Duration grace) {
        registry.beforeGivingUp = id -> {
            lateClaim.set(registry.claim("exec_1"));
            lateComplete.set(registry.complete("exec_1", ApprovalDecision.approved("bob", null)));
        };
        var t = new Thread(() -> {
            try {
                outcome.set(registry.waitForApproval("exec_1", timeout,
                        new ApprovalWaitRegistry.WaitContext("carol", "x", "d1"), grace));
            } catch (Exception e) {
                outcome.set(e);
            }
        });
        t.start();
        return t;
    }

    private void awaitPending() throws InterruptedException {
        while (!registry.isPending("exec_1")) {
            Thread.sleep(1);
        }
    }

    @Test
    void aDecisionArrivingAsTheGraceRunsOutIsNotDelivered() throws Exception {
        var t = waiting(Duration.ofMillis(100), Duration.ofMillis(100));
        awaitPending();
        assertThat(registry.claim("exec_1")).as("a decision claims the call, then never completes").isTrue();
        t.join(5000);

        assertThat(outcome.get()).isInstanceOf(TimeoutException.class);
        assertThat(lateComplete.get()).as("delivery after give-up must fail, so the void path runs").isFalse();
    }

    @Test
    void aDecisionArrivingAsTheTimeoutFiresIsNotDelivered() throws Exception {
        var t = waiting(Duration.ofMillis(100), Duration.ofSeconds(10));
        awaitPending();
        t.join(5000);

        assertThat(outcome.get()).isInstanceOf(TimeoutException.class);
        assertThat(lateClaim.get()).isFalse();
        assertThat(lateComplete.get()).isFalse();
    }

    @Test
    void aDecisionArrivingAsTheWaitIsInterruptedIsNotDelivered() throws Exception {
        var t = waiting(Duration.ofSeconds(30), Duration.ofSeconds(10));
        awaitPending();
        t.interrupt();
        t.join(5000);

        assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        assertThat(lateClaim.get()).as("an interrupted wait cannot be claimed").isFalse();
        assertThat(lateComplete.get()).isFalse();
    }
}
