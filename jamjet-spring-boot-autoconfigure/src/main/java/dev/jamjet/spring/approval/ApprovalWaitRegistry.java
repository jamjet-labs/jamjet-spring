package dev.jamjet.spring.approval;

import dev.jamjet.spring.client.model.ApprovalDecision;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Registry tracking pending approval requests. The advisor blocks on
 * {@link #waitForApproval}, and the controller calls {@link #complete}
 * when a decision arrives.
 */
public class ApprovalWaitRegistry {

    private final ConcurrentHashMap<String, SynchronousQueue<ApprovalDecision>> pending =
            new ConcurrentHashMap<>();

    /**
     * Block the current thread until an approval decision is received
     * for the given execution, or the timeout elapses.
     */
    public ApprovalDecision waitForApproval(String executionId, Duration timeout)
            throws TimeoutException, InterruptedException {
        var queue = pending.computeIfAbsent(executionId, k -> new SynchronousQueue<>());
        try {
            ApprovalDecision decision = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (decision == null) {
                throw new TimeoutException("Approval timed out for execution " + executionId);
            }
            return decision;
        } finally {
            pending.remove(executionId);
        }
    }

    /**
     * Complete a pending approval request. Called by the approval controller
     * when a webhook callback is received.
     */
    public boolean complete(String executionId, ApprovalDecision decision) {
        var queue = pending.get(executionId);
        if (queue == null) {
            return false;
        }
        return queue.offer(decision);
    }

    /** List all execution IDs currently waiting for approval. */
    public List<PendingApproval> allPending() {
        return pending.keySet().stream()
                .map(id -> new PendingApproval(id))
                .toList();
    }

    public record PendingApproval(String executionId) {
    }
}
