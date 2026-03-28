package dev.jamjet.spring.approval;

import dev.jamjet.spring.client.model.ApprovalDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalWaitRegistryTest {

    @Test
    void approvalCompletesWaitingThread() throws Exception {
        var registry = new ApprovalWaitRegistry();
        var decision = ApprovalDecision.approved("user-1", "looks good");

        // Complete in a separate thread after a short delay
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            registry.complete("exec-1", decision);
        });

        var result = registry.waitForApproval("exec-1", Duration.ofSeconds(5));
        assertThat(result.decision()).isEqualTo("approved");
        assertThat(result.userId()).isEqualTo("user-1");
    }

    @Test
    void waitTimesOutWhenNoDecision() {
        var registry = new ApprovalWaitRegistry();

        assertThatThrownBy(() -> registry.waitForApproval("exec-2", Duration.ofMillis(50)))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void pendingListReflectsWaitingExecutions() {
        var registry = new ApprovalWaitRegistry();

        // Start a wait in background
        Thread.startVirtualThread(() -> {
            try {
                registry.waitForApproval("exec-3", Duration.ofSeconds(10));
            } catch (Exception e) {
                // expected
            }
        });

        // Give it a moment to register
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var pending = registry.allPending();
        assertThat(pending).anyMatch(p -> p.executionId().equals("exec-3"));

        // Complete it to clean up
        registry.complete("exec-3", ApprovalDecision.approved("test", null));
    }

    @Test
    void completeReturnsFalseWhenNoWaiter() {
        var registry = new ApprovalWaitRegistry();
        boolean result = registry.complete("nonexistent", ApprovalDecision.approved("user", null));
        assertThat(result).isFalse();
    }
}
