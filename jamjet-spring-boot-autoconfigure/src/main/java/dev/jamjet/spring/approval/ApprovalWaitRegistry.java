package dev.jamjet.spring.approval;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.model.ApprovalDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry of ChatClient calls paused for approval. The advisor blocks on
 * {@link #waitForApproval}; the controller calls {@link #complete} when a
 * decision arrives.
 *
 * <p>A decision delivered after the waiter registered but before it started
 * blocking is not lost: each wait is a {@link CompletableFuture}, so
 * {@link #complete} succeeds whenever a waiter is registered.
 */
public class ApprovalWaitRegistry {

    /**
     * What the paused call carries for its reviewer: who triggered it (null when
     * unknown), a bounded preview of the prompt, and the digest a decision must echo.
     */
    public record WaitContext(String initiator, String promptPreview, String contentDigest) {
        public static final WaitContext NONE = new WaitContext(null, null, null);
    }

    private enum State { WAITING, DECIDING, TIMED_OUT }

    private record Waiter(CompletableFuture<ApprovalDecision> decision, Instant since, WaitContext context,
                          AtomicReference<State> state) {}

    /**
     * Default for how long a call whose timeout fired keeps waiting for a
     * decision that was already being recorded when it fired. The advisor passes
     * one derived from the client's own timeouts.
     */
    static final Duration DEFAULT_DECIDING_GRACE = decidingGrace(new JamjetProperties());

    /**
     * How long a call whose timeout fired keeps waiting for a decision that was
     * already being recorded: longer than the client can take to record it
     * (connect plus read timeout), plus a margin. The one place this is defined.
     */
    public static Duration decidingGrace(JamjetProperties properties) {
        return Duration.ofSeconds(properties.getConnectTimeoutSeconds())
                .plusSeconds(properties.getReadTimeoutSeconds())
                .plusSeconds(10);
    }

    private final ConcurrentHashMap<String, Waiter> pending = new ConcurrentHashMap<>();

    /**
     * Block the current thread until a decision is received for the given
     * execution, or the timeout elapses.
     */
    public ApprovalDecision waitForApproval(String executionId, Duration timeout)
            throws TimeoutException, InterruptedException {
        return waitForApproval(executionId, timeout, WaitContext.NONE);
    }

    /** As {@link #waitForApproval(String, Duration)}, recording what the reviewer is shown. */
    public ApprovalDecision waitForApproval(String executionId, Duration timeout, WaitContext context)
            throws TimeoutException, InterruptedException {
        return waitForApproval(executionId, timeout, context, DEFAULT_DECIDING_GRACE);
    }

    /**
     * As {@link #waitForApproval(String, Duration, WaitContext)}, waiting up to
     * {@code decidingGrace} more for a decision that claimed the call before the
     * timeout fired. Every exit other than a delivered decision is a rejection by
     * the caller.
     */
    public ApprovalDecision waitForApproval(String executionId, Duration timeout, WaitContext context,
                                            Duration decidingGrace)
            throws TimeoutException, InterruptedException {
        var waiter = new Waiter(new CompletableFuture<>(), Instant.now(),
                context == null ? WaitContext.NONE : context, new AtomicReference<>(State.WAITING));
        if (pending.putIfAbsent(executionId, waiter) != null) {
            // Never share a wait: one decision would release both calls, and only
            // the first call's content would have been reviewed.
            throw new IllegalStateException("another call is already waiting for approval on execution "
                    + executionId);
        }
        try {
            try {
                return waiter.decision().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (waiter.state().compareAndSet(State.WAITING, State.TIMED_OUT)) {
                    return giveUp(waiter, new TimeoutException("Approval timed out for execution " + executionId));
                }
                // A decision claimed the call before the timeout fired and is being
                // recorded: apply it rather than contradict what the runtime records.
                try {
                    return waiter.decision().get(decidingGrace.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException late) {
                    return giveUp(waiter, new TimeoutException("Approval timed out for execution " + executionId
                            + " while a decision was being recorded"));
                }
            }
        } catch (InterruptedException e) {
            ApprovalDecision won = settle(waiter);
            if (won != null) {
                Thread.currentThread().interrupt();
                return won;
            }
            beforeGivingUp.accept(executionId);
            throw e;
        } catch (ExecutionException e) {
            throw new IllegalStateException("Approval wait failed for execution " + executionId, e.getCause());
        } finally {
            pending.remove(executionId, waiter);
        }
    }

    /**
     * Settle the wait before giving up, so no decision can be delivered to a call
     * that is already being rejected: a later {@link #complete} returns false and
     * the controller voids the recorded decision. If a decision won the race, it
     * is returned instead and applies.
     */
    private ApprovalDecision giveUp(Waiter waiter, TimeoutException timeout) throws TimeoutException {
        ApprovalDecision won = settle(waiter);
        if (won != null) {
            return won;
        }
        beforeGivingUp.accept(timeout.getMessage());
        throw timeout;
    }

    private static ApprovalDecision settle(Waiter waiter) {
        waiter.state().compareAndSet(State.WAITING, State.TIMED_OUT);
        if (waiter.decision().completeExceptionally(new CancellationException("the call stopped waiting"))) {
            return null;
        }
        return waiter.decision().isCompletedExceptionally() ? null : waiter.decision().getNow(null);
    }

    /** Test hook: runs after a wait was settled as not delivered, before it is removed. */
    volatile java.util.function.Consumer<String> beforeGivingUp = id -> { };

    /**
     * Claim the waiting call for one decision. Exactly one caller wins; a second
     * decision, or one arriving after the call timed out, gets {@code false}
     * and must record nothing. A claimed call stays registered until
     * {@link #complete} releases it.
     */
    public boolean claim(String executionId) {
        var waiter = pending.get(executionId);
        return waiter != null && waiter.state().compareAndSet(State.WAITING, State.DECIDING);
    }

    /**
     * Deliver a decision to the call waiting on this execution.
     *
     * @return false if no call is waiting
     */
    public boolean complete(String executionId, ApprovalDecision decision) {
        var waiter = pending.get(executionId);
        if (waiter == null) {
            return false;
        }
        return waiter.decision().complete(decision);
    }

    /** The waiting call's context, or null if no call is waiting. */
    public WaitContext context(String executionId) {
        var waiter = pending.get(executionId);
        return waiter == null ? null : waiter.context();
    }

    /** True if a call is waiting for a decision on this execution. */
    public boolean isPending(String executionId) {
        return pending.containsKey(executionId);
    }

    /** All calls currently waiting, oldest first. */
    public List<PendingApproval> allPending() {
        return pending.entrySet().stream()
                .map(e -> new PendingApproval(e.getKey(), e.getValue().since(), e.getValue().context()))
                .sorted(Comparator.comparing(PendingApproval::waitingSince))
                .toList();
    }

    public record PendingApproval(String executionId, Instant waitingSince, WaitContext context) {
    }
}
