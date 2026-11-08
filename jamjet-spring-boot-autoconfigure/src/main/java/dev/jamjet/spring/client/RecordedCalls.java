package dev.jamjet.spring.client;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.client.model.ClaimedWorkItem;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import dev.jamjet.spring.client.model.StartExecutionResponse;
import dev.jamjet.spring.client.model.WorkItemHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The application's side of a recorded call: registers the workflow
 * ({@link RecordedCallWorkflow}), starts one execution per call, and holds the
 * execution's node for as long as the call runs.
 *
 * <p>On a runtime with {@code external_fn} nodes the node's work item goes to a
 * queue of the application's own. {@link #start} claims it right after the
 * execution starts and keeps the lease renewed; {@link #finish} completes it with
 * the call's outcome, or fails it when the outcome's {@code status} is
 * {@code failed}, echoing the claim's {@code lease_fence} and
 * {@code idempotency_key}. So the execution is {@code running} while the call
 * runs and {@code completed} or {@code failed} when it ends, and if this process
 * stops mid-call its lease lapses and the runtime fails the execution.
 *
 * <p>A claim hands out the oldest item on the queue, not a chosen one. When two
 * calls start at once (in this process or in another instance of the
 * application), a claim can take the other call's node. A node claimed for a call
 * running in this JVM goes to that call. A node claimed for a call running
 * elsewhere is held here and completed from that call's outcome event (the caller
 * appends it before {@link #finish}); if none is reported within
 * {@code unreportedAfter}, the node is failed.
 *
 * <p>On a runtime that predates {@code external_fn} nodes (JamJet 0.5.x) the
 * {@value RecordedCallWorkflow#LEGACY_VERSION} {@code wait} workflow is registered
 * instead, nothing is claimed, and {@link #finish} does nothing: that runtime
 * completes the node as soon as the execution starts, and the record is the
 * execution's events.
 *
 * <p>Thread-safe. While it holds a node it runs one daemon thread that renews
 * the leases; the thread stops when nothing is held, and {@link #close} stops it.
 */
public class RecordedCalls implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordedCalls.class);

    /** Lease asked for on each claimed node. It is renewed every third of it while the call runs. */
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(60);

    /** How long a node held for a call another process runs waits for that call's outcome before it is failed. */
    public static final Duration DEFAULT_UNREPORTED_AFTER = Duration.ofHours(1);

    /** Most claims one start makes looking for its own node. */
    static final int MAX_CLAIMS_PER_START = 8;

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Duration RECHECK = Duration.ofSeconds(2);

    /**
     * Calls running in this JVM, keyed by runtime, queue and execution id, so a
     * node one instance claims for another instance's call goes to that instance.
     */
    private static final ConcurrentHashMap<String, RecordedCalls> RUNNING_HERE = new ConcurrentHashMap<>();

    /** What a recorded call is registered as and reports. */
    public record Spec(String workflowId, String nodeId, String completedEvent, String name, String source,
                       String queue, String handler, String description) {

        /** A call: queue {@code <workflowId>.calls}, handler {@value RecordedCallWorkflow#CALL_HANDLER}. */
        public static Spec call(String workflowId, String nodeId, String completedEvent, String name, String source) {
            String queue = RecordedCallWorkflow.queueFor(workflowId);
            return new Spec(workflowId, nodeId, completedEvent, name, source, queue,
                    RecordedCallWorkflow.CALL_HANDLER, RecordedCallWorkflow.callDescription(name, queue, completedEvent));
        }

        public Map<String, Object> ir() {
            return RecordedCallWorkflow.ir(workflowId, nodeId, name, source, queue, handler, description);
        }

        public Map<String, Object> legacyIr() {
            return RecordedCallWorkflow.legacyIr(workflowId, nodeId, completedEvent, name, source);
        }
    }

    private final JamjetRuntimeClient client;
    private final Spec spec;
    private final boolean claims;
    private final Duration lease;
    private final Duration unreportedAfter;
    private final String workerId;
    private final String keyPrefix;

    private final Object registrationLock = new Object();
    private volatile String registeredVersion;

    private final Object claimLock = new Object();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Held> held = new ConcurrentHashMap<>();

    private ScheduledExecutorService sweeper; // guarded by this
    private volatile boolean closed;
    private volatile boolean manualUpkeep;

    /** A claimed node: its item, the lease's fence, and the outcome to settle it with once known. */
    private static final class Held {
        final ClaimedWorkItem item;
        final String claimedBy;
        final Instant claimedAt;
        volatile long fence;
        volatile Instant renewedAt;
        volatile Instant checkedAt = Instant.EPOCH;
        volatile Map<String, Object> outcome;

        Held(ClaimedWorkItem item, String claimedBy) {
            this.item = item;
            this.claimedBy = claimedBy;
            this.claimedAt = Instant.now();
            this.renewedAt = claimedAt;
            this.fence = item.leaseFence();
        }
    }

    /** Records calls: claims each call's node and completes it when the call ends. */
    public RecordedCalls(JamjetRuntimeClient client, Spec spec) {
        this(client, spec, true, DEFAULT_LEASE, DEFAULT_UNREPORTED_AFTER);
    }

    /**
     * @param claims          false to start executions without ever claiming their node, for a
     *                        record that stays open (a chat memory)
     * @param lease           lease asked for on each claimed node (the runtime holds it to 5 s to 1 h)
     * @param unreportedAfter how long a node held for a call another process runs waits for its outcome
     */
    public RecordedCalls(JamjetRuntimeClient client, Spec spec, boolean claims, Duration lease,
                         Duration unreportedAfter) {
        this.client = client;
        this.spec = spec;
        this.claims = claims;
        this.lease = lease == null ? DEFAULT_LEASE : lease;
        this.unreportedAfter = unreportedAfter == null ? DEFAULT_UNREPORTED_AFTER : unreportedAfter;
        this.workerId = "jamjet-spring-" + ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        this.keyPrefix = client.endpointKey() + "|" + spec.queue() + "|";
    }

    public Spec spec() {
        return spec;
    }

    /**
     * The version registered: {@value RecordedCallWorkflow#VERSION}, or
     * {@value RecordedCallWorkflow#LEGACY_VERSION} on a runtime without
     * {@code external_fn} nodes. {@code null} before the first registration.
     */
    public String registeredVersion() {
        return registeredVersion;
    }

    /** Registers the workflow unless this instance already did; returns the version registered. */
    public String register() {
        String version = registeredVersion;
        if (version != null) {
            return version;
        }
        synchronized (registrationLock) {
            if (registeredVersion == null) {
                try {
                    client.createWorkflow(spec.ir());
                    registeredVersion = RecordedCallWorkflow.VERSION;
                } catch (JamjetClientException e) {
                    if (!predatesExternalFn(e)) {
                        throw e;
                    }
                    log.warn("The JamJet runtime does not know external_fn nodes (it predates them, as 0.5.x does), "
                            + "so {} is registered as v{} with a wait node, which that runtime completes as soon as "
                            + "an execution starts: each call is recorded by its events only. Upgrade the runtime "
                            + "to hold each call's execution open until the call ends.",
                            spec.workflowId(), RecordedCallWorkflow.LEGACY_VERSION);
                    client.createWorkflow(spec.legacyIr());
                    registeredVersion = RecordedCallWorkflow.LEGACY_VERSION;
                }
            }
            return registeredVersion;
        }
    }

    /** True for the runtime's refusal of an IR because it has no {@code external_fn} node kind. */
    static boolean predatesExternalFn(JamjetClientException e) {
        String message = e.getRuntimeMessage();
        return e.getStatusCode() == 400 && message != null && message.contains("unknown variant `external_fn`");
    }

    /**
     * Starts an execution with {@code input} (registering the workflow first, and
     * again if the runtime has forgotten it) and, on the
     * {@value RecordedCallWorkflow#VERSION} workflow, claims its node.
     *
     * @return the execution id
     * @throws JamjetClientException  when the execution cannot be started
     * @throws JamjetGovernanceException when the runtime refused or held the call's node under its policy
     */
    public String start(Map<String, Object> input) {
        String version = register();
        StartExecutionResponse started;
        try {
            started = client.startExecution(spec.workflowId(), version, input);
        } catch (JamjetClientException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
            // The runtime no longer knows the workflow (its store was reset):
            // register it again and retry once. A second failure is the caller's.
            log.info("Runtime does not know workflow {}; registering it again", spec.workflowId());
            synchronized (registrationLock) {
                registeredVersion = null;
            }
            version = register();
            started = client.startExecution(spec.workflowId(), version, input);
        }
        if (started == null || started.executionId() == null || started.executionId().isBlank()) {
            throw new JamjetClientException("the runtime returned no execution_id", "/executions");
        }
        String executionId = started.executionId();
        if (claims && RecordedCallWorkflow.VERSION.equals(version) && !closed) {
            running.add(executionId);
            RUNNING_HERE.put(keyPrefix + executionId, this);
            claimFor(executionId);
        }
        return executionId;
    }

    /**
     * Settles the call's node with {@code outcome}: completes it, or fails it when
     * {@code outcome.status} is {@code failed}. Call it after the outcome event was
     * appended. Never throws: when the node cannot be settled here, whoever holds it
     * settles it from that event.
     */
    public void finish(String executionId, Map<String, Object> outcome) {
        if (executionId == null || !claims) {
            return;
        }
        running.remove(executionId);
        RUNNING_HERE.remove(keyPrefix + executionId, this);
        Held h = held.remove(executionId);
        if (h == null) {
            // Another instance claimed it (two calls started at once), or no claim
            // reached it yet: whoever holds it settles it from the outcome event.
            log.debug("Execution {}: its node is not held here; it is settled from the {} event",
                    executionId, spec.completedEvent());
            return;
        }
        settle(h, outcome == null ? Map.of() : outcome);
    }

    /** Executions whose node this instance holds (for tests and metrics). */
    Set<String> heldExecutions() {
        return Set.copyOf(held.keySet());
    }

    /**
     * Stops renewing leases. A node held for a call in another instance whose
     * outcome is already recorded is settled first; any other held node's lease
     * lapses, and the runtime fails that execution.
     */
    @Override
    public void close() {
        closed = true;
        for (String executionId : running) {
            RUNNING_HERE.remove(keyPrefix + executionId, this);
        }
        synchronized (this) {
            if (sweeper != null) {
                sweeper.shutdownNow();
                sweeper = null;
            }
        }
        for (Held h : held.values()) {
            String executionId = h.item.executionId();
            if (running.contains(executionId)) {
                continue;
            }
            try {
                Map<String, Object> outcome = h.outcome != null ? h.outcome : reportedOutcome(executionId);
                if (outcome != null && held.remove(executionId, h)) {
                    settle(h, outcome);
                }
            } catch (JamjetClientException e) {
                log.debug("Execution {}: could not settle its node on close: {}", executionId, e.getMessage());
            }
        }
    }

    // ── Claiming ──────────────────────────────────────────────────────────────

    private void claimFor(String executionId) {
        boolean answered = false;
        try {
            synchronized (claimLock) {
                for (int i = 0; i < MAX_CLAIMS_PER_START && !held.containsKey(executionId); i++) {
                    ClaimedWorkItem item = client.claimWorkItem(workerId, List.of(spec.queue()), lease);
                    answered = true;
                    if (item == null) {
                        break;
                    }
                    route(item, workerId);
                }
            }
        } catch (JamjetClientException e) {
            // The call is recorded either way (the execution exists and its events
            // are appended); its node waits on the queue for a later claim.
            log.warn("Execution {}: could not claim its node from queue {} ({}); the execution stays running until "
                    + "a later claim settles it from the {} event", executionId, spec.queue(), e.getMessage(),
                    spec.completedEvent());
            return;
        }
        if (answered && !held.containsKey(executionId)) {
            refuseIfTheRuntimeStoppedTheCall(executionId);
        }
    }

    /** Give a claimed node to the call it belongs to: here, another instance in this JVM, or held for a call elsewhere. */
    private void route(ClaimedWorkItem item, String claimedBy) {
        RecordedCalls owner = RUNNING_HERE.get(keyPrefix + item.executionId());
        RecordedCalls target = owner != null && !owner.closed ? owner : this;
        if (target == this && !running.contains(item.executionId())) {
            log.debug("Holding the node of execution {}, a call this instance does not run, until its {} event",
                    item.executionId(), spec.completedEvent());
        }
        target.held.put(item.executionId(), new Held(item, claimedBy));
        target.ensureSweeper();
    }

    /**
     * The claim handed out nothing for this execution. Usually another instance
     * took its node in a race, which is harmless. But the runtime also hands out
     * nothing for a node its policy blocked (it fails the execution) or holds for
     * approval: the call is then refused, since the application cannot run a call
     * the runtime stopped.
     */
    private void refuseIfTheRuntimeStoppedTheCall(String executionId) {
        String refusal;
        try {
            ExecutionState state = client.getExecution(executionId);
            String status = state == null ? null : state.status();
            if (status != null && !"running".equalsIgnoreCase(status) && !"paused".equalsIgnoreCase(status)) {
                refusal = "the runtime ended execution " + executionId + " (" + status + ") before the call ran"
                        + failureReason(executionId);
            } else if (client.listApprovals(executionId).pending().stream()
                    .anyMatch(p -> spec.nodeId().equals(p.nodeId()))) {
                refusal = "the runtime holds node " + spec.nodeId() + " of execution " + executionId
                        + " for approval under its policy, and this application does not wait for approvals the "
                        + "runtime holds; the execution was cancelled";
                cancelQuietly(executionId);
            } else {
                return;
            }
        } catch (JamjetClientException e) {
            release(executionId);
            throw e;
        }
        release(executionId);
        throw new JamjetGovernanceException("JamJet runtime refused this call: " + refusal, null);
    }

    private String failureReason(String executionId) {
        try {
            String reason = null;
            for (ExecutionEvent e : client.listEvents(executionId)) {
                if ("node_failed".equals(e.type()) && e.field("error") != null) {
                    reason = String.valueOf(e.field("error"));
                }
            }
            return reason == null ? "" : ": " + reason;
        } catch (JamjetClientException e) {
            return "";
        }
    }

    private void cancelQuietly(String executionId) {
        try {
            client.cancelExecution(executionId);
        } catch (JamjetClientException e) {
            log.warn("Could not cancel execution {}: {}", executionId, e.getMessage());
        }
    }

    private void release(String executionId) {
        running.remove(executionId);
        RUNNING_HERE.remove(keyPrefix + executionId, this);
        held.remove(executionId);
    }

    // ── Settling ──────────────────────────────────────────────────────────────

    private void settle(Held h, Map<String, Object> outcome) {
        String executionId = h.item.executionId();
        try {
            if ("failed".equals(outcome.get("status"))) {
                Object error = outcome.get("error");
                client.failWorkItem(h.item.id(), error == null ? "the call failed" : String.valueOf(error), h.fence);
            } else {
                client.completeWorkItem(h.item.id(), outcome, Duration.between(h.claimedAt, Instant.now()).toMillis(),
                        h.fence, h.item.idempotencyKey());
            }
        } catch (JamjetClientException e) {
            if (e.isClientError()) {
                // 409: the lease is no longer held or the execution already ended
                // (cancelled, or its lease lapsed). Nothing to retry.
                log.warn("Execution {}: the runtime did not take the call's outcome on its node ({}); the outcome is "
                        + "recorded as the {} event", executionId, e.getRuntimeMessage(), spec.completedEvent());
                return;
            }
            // Transport failure or 5xx: keep the lease and retry from the sweeper.
            log.debug("Execution {}: could not settle its node yet ({}); retrying", executionId, e.getMessage());
            h.outcome = outcome;
            h.checkedAt = Instant.now();
            held.put(executionId, h);
            ensureSweeper();
        }
    }

    // ── Lease upkeep ──────────────────────────────────────────────────────────

    /** For tests: no background thread; the test calls {@link #sweep(Instant)} itself. */
    RecordedCalls manualUpkeep() {
        manualUpkeep = true;
        return this;
    }

    private synchronized void ensureSweeper() {
        if (sweeper != null || closed || manualUpkeep) {
            return;
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jamjet-recorded-calls-" + spec.workflowId());
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void sweep() {
        sweep(Instant.now());
    }

    /** One round of lease upkeep as of {@code now}: renew, settle from the log, give up. */
    void sweep(Instant now) {
        for (Held h : held.values()) {
            String executionId = h.item.executionId();
            try {
                if (now.isAfter(h.renewedAt.plus(lease))) {
                    // Not renewed in time (the runtime was unreachable): the runtime has
                    // failed the node by now, so the fence is no longer ours.
                    held.remove(executionId, h);
                    log.warn("Execution {}: the lease on its node lapsed before the call's outcome was settled",
                            executionId);
                    continue;
                }
                if (h.outcome != null) {
                    if (now.isAfter(h.checkedAt.plus(RECHECK)) && held.remove(executionId, h)) {
                        settle(h, h.outcome);
                    }
                    continue;
                }
                if (running.contains(executionId) && now.isAfter(h.claimedAt.plus(unreportedAfter))) {
                    // A call that never finished (its thread died without reporting):
                    // stop renewing, so the lease lapses and the runtime fails it.
                    held.remove(executionId, h);
                    running.remove(executionId);
                    RUNNING_HERE.remove(keyPrefix + executionId, this);
                    log.warn("Execution {}: the call has run for more than {} without finishing; its node is released "
                            + "and the runtime will fail the execution when the lease lapses", executionId,
                            unreportedAfter);
                    continue;
                }
                if (!running.contains(executionId) && now.isAfter(h.checkedAt.plus(RECHECK))) {
                    h.checkedAt = now;
                    Map<String, Object> reported = reportedOutcome(executionId);
                    if (reported != null) {
                        if (held.remove(executionId, h)) {
                            settle(h, reported);
                        }
                        continue;
                    }
                    if (now.isAfter(h.claimedAt.plus(unreportedAfter))) {
                        if (held.remove(executionId, h)) {
                            var failure = new LinkedHashMap<String, Object>();
                            failure.put("status", "failed");
                            failure.put("error", "no outcome was reported for this call within " + unreportedAfter
                                    + "; the application instance that ran it may have stopped");
                            settle(h, failure);
                        }
                        continue;
                    }
                }
                if (now.isAfter(h.renewedAt.plus(lease.dividedBy(3)))) {
                    WorkItemHeartbeat beat = client.heartbeatWorkItem(h.item.id(), h.claimedBy, h.fence);
                    h.renewedAt = now;
                    if (beat != null && beat.leaseFence() > 0) {
                        h.fence = beat.leaseFence();
                    }
                    if (beat != null && beat.cancelRequested()) {
                        held.remove(executionId, h);
                        log.info("Execution {} ended on the runtime (cancelled?) while its call was running",
                                executionId);
                    }
                }
            } catch (JamjetClientException e) {
                if (e.getStatusCode() == 409) {
                    held.remove(executionId, h);
                    log.warn("Execution {}: its node's lease is no longer held ({})", executionId,
                            e.getRuntimeMessage());
                } else {
                    log.debug("Execution {}: lease upkeep failed, retrying: {}", executionId, e.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("Execution {}: unexpected failure keeping its node's lease", executionId, e);
            }
        }
        stopSweeperIfIdle();
    }

    /** No thread while nothing is held, so an instance nobody uses any more can be collected. */
    private synchronized void stopSweeperIfIdle() {
        if (sweeper != null && held.isEmpty()) {
            sweeper.shutdown();
            sweeper = null;
        }
    }

    /** The payload of the execution's latest outcome event, or null if none was recorded. */
    private Map<String, Object> reportedOutcome(String executionId) {
        Map<String, Object> latest = null;
        for (ExecutionEvent e : client.listEvents(executionId)) {
            if (e.isExternalEvent(spec.completedEvent())) {
                latest = e.payload() == null ? Map.of() : e.payload();
            }
        }
        return latest;
    }
}
