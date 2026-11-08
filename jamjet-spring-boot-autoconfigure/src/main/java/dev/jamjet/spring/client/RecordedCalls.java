package dev.jamjet.spring.client;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.client.model.ClaimedWorkItem;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.StartExecutionResponse;
import dev.jamjet.spring.client.model.WorkItemHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The application's side of a recorded call: registers the workflow
 * ({@link RecordedCallWorkflow}), starts one execution per call, and holds the
 * execution's node for as long as the call runs.
 *
 * <p>On a runtime with {@code external_fn} nodes the node's work item goes to a
 * queue of the application's own. {@link #start} claims it before the call runs
 * and keeps the lease renewed; {@link #finish} completes it with the call's
 * outcome, or fails it when the outcome's {@code status} is {@code failed},
 * echoing the claim's {@code lease_fence} and {@code idempotency_key}. So the
 * execution is {@code running} while the call runs and {@code completed} or
 * {@code failed} when it ends, and if this process stops mid-call its lease
 * lapses and the runtime fails the execution.
 *
 * <p><b>Claims cross.</b> A claim hands out the oldest item on the queue, not a
 * chosen one, so calls that start together (in this process or in another
 * instance) claim each other's nodes. A node claimed for a call running in this
 * JVM goes to that call. A node claimed for a call running elsewhere is held
 * here: this instance appends a {@value #HELD_EVENT} event to that execution, so
 * its owner knows the node was handed out, and completes the node from the
 * call's outcome event (the caller appends it before {@link #finish}), or fails
 * it if none is reported within {@code unreportedAfter}.
 *
 * <p><b>The runtime decides first.</b> The runtime applies its policy when a node
 * is claimed, whoever claims it: a blocked node is failed, a gated one is held
 * for approval, and the claim hands out nothing. So {@link #start} does not let
 * the call run until it knows what happened to its own node: it claimed it,
 * another instance holds it ({@value #HELD_EVENT}), or the runtime recorded a
 * block or an approval request for it, which refuses the call
 * ({@link JamjetGovernanceException}). If none of these is known within
 * {@code claimWait}, the start fails like any other runtime error.
 *
 * <p>On a runtime that predates {@code external_fn} nodes (JamJet 0.5.x) the
 * {@value RecordedCallWorkflow#LEGACY_VERSION} {@code wait} workflow is registered
 * instead, nothing is claimed, and {@link #finish} does nothing: that runtime
 * completes the node as soon as the execution starts, and the record is the
 * execution's events. The current workflow is tried again every 15 minutes, so
 * a runtime upgraded in place is picked up.
 *
 * <p>Thread-safe. While it holds a node it runs one daemon thread that renews
 * the leases; the thread stops when nothing is held, and {@link #close} stops it.
 */
public class RecordedCalls implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordedCalls.class);

    /** Lease asked for on each claimed node. It is renewed every third of the lease the runtime grants. */
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(60);

    /** How long a node held for a call another process runs waits for that call's outcome before it is failed. */
    public static final Duration DEFAULT_UNREPORTED_AFTER = Duration.ofHours(1);

    /** How long a start waits to learn what happened to its node before it fails. */
    public static final Duration DEFAULT_CLAIM_WAIT = Duration.ofSeconds(10);

    /** Correlation key of the event an instance appends to an execution whose node it holds for another instance. */
    public static final String HELD_EVENT = "jamjet_spring.node_held";

    /** Longest a single request made while holding a lease may take. */
    private static final Duration MAX_REQUEST = Duration.ofSeconds(10);

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Duration RECHECK = Duration.ofSeconds(2);
    private static final Duration RECHECK_SLOW = Duration.ofSeconds(10);
    private static final Duration REPROBE = Duration.ofMinutes(15);
    private static final Duration DRAIN = Duration.ofSeconds(5);

    /**
     * Calls running in this JVM, keyed by runtime, queue and execution id, so a
     * node one instance claims for another instance's call goes to that instance.
     */
    private static final ConcurrentHashMap<String, RecordedCalls> RUNNING_HERE = new ConcurrentHashMap<>();

    /**
     * What a recorded call is registered as and reports. A non-null
     * {@code startOutcome} completes the node with it as soon as the execution
     * starts, for a record that outlives any one call (a chat memory).
     */
    public record Spec(String workflowId, String nodeId, String completedEvent, String name, String source,
                       String queue, String handler, String description, Map<String, Object> startOutcome) {

        /** A call: queue {@code <workflowId>.calls}, handler {@value RecordedCallWorkflow#CALL_HANDLER}. */
        public static Spec call(String workflowId, String nodeId, String completedEvent, String name, String source) {
            String queue = RecordedCallWorkflow.queueFor(workflowId);
            return new Spec(workflowId, nodeId, completedEvent, name, source, queue,
                    RecordedCallWorkflow.CALL_HANDLER, RecordedCallWorkflow.callDescription(name, queue, completedEvent),
                    null);
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
    private final Duration lease;
    private final Duration unreportedAfter;
    private final Duration claimWait;
    private final String workerId;
    private final String keyPrefix;

    private final Object registrationLock = new Object();
    private volatile String registeredVersion;
    private volatile Instant legacySince;

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
        final Duration lease;
        volatile long fence;
        volatile Instant renewedAt;
        volatile Instant checkedAt = Instant.EPOCH;
        volatile Map<String, Object> outcome;

        Held(ClaimedWorkItem item, String claimedBy, Duration askedFor) {
            this.item = item;
            this.claimedBy = claimedBy;
            this.claimedAt = Instant.now();
            this.renewedAt = claimedAt;
            this.fence = item.leaseFence();
            Long granted = item.leaseSecs();
            this.lease = granted != null && granted > 0 ? Duration.ofSeconds(granted) : askedFor;
        }
    }

    /** Records calls: claims each call's node and completes it when the call ends. */
    public RecordedCalls(JamjetRuntimeClient client, Spec spec) {
        this(client, spec, DEFAULT_LEASE, DEFAULT_UNREPORTED_AFTER);
    }

    /**
     * @param lease           lease asked for on each claimed node (the runtime holds it to 5 s to 1 h)
     * @param unreportedAfter how long a node held for a call another process runs waits for its outcome,
     *                        and the longest a call here may hold its own node without finishing
     */
    public RecordedCalls(JamjetRuntimeClient client, Spec spec, Duration lease, Duration unreportedAfter) {
        this(client, spec, lease, unreportedAfter, DEFAULT_CLAIM_WAIT);
    }

    RecordedCalls(JamjetRuntimeClient client, Spec spec, Duration lease, Duration unreportedAfter,
                  Duration claimWait) {
        this.client = client;
        this.spec = spec;
        this.lease = lease == null ? DEFAULT_LEASE : lease;
        this.unreportedAfter = unreportedAfter == null ? DEFAULT_UNREPORTED_AFTER : unreportedAfter;
        this.claimWait = claimWait == null ? DEFAULT_CLAIM_WAIT : claimWait;
        this.workerId = "jamjet-spring-" + ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        this.keyPrefix = client.endpointKey() + "|" + spec.queue() + "|";
    }

    /**
     * One instance per client and workflow, for callers that have no lifecycle to
     * close one with (a LangChain4j proxy, for example). It lives as long as the
     * client, and its thread only runs while it holds a node.
     */
    public static RecordedCalls shared(JamjetRuntimeClient client, Spec spec) {
        return client.recordedCalls.computeIfAbsent(spec.workflowId(), k -> new RecordedCalls(client, spec));
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
        Instant since = legacySince;
        if (version != null && (since == null || Instant.now().isBefore(since.plus(REPROBE)))) {
            return version;
        }
        synchronized (registrationLock) {
            boolean reprobe = RecordedCallWorkflow.LEGACY_VERSION.equals(registeredVersion)
                    && legacySince != null && !Instant.now().isBefore(legacySince.plus(REPROBE));
            if (registeredVersion == null || reprobe) {
                try {
                    client.createWorkflow(spec.ir());
                    if (reprobe) {
                        log.info("The JamJet runtime now knows external_fn nodes; {} is recorded as v{} from now on",
                                spec.workflowId(), RecordedCallWorkflow.VERSION);
                    }
                    registeredVersion = RecordedCallWorkflow.VERSION;
                    legacySince = null;
                } catch (JamjetClientException e) {
                    if (reprobe) {
                        // Keep recording with the legacy workflow and try again later.
                        legacySince = Instant.now();
                        return registeredVersion;
                    }
                    if (!predatesExternalFn(e)) {
                        throw e;
                    }
                    log.warn("The JamJet runtime does not know external_fn nodes (it predates them, as 0.5.x does), "
                                    + "so {} is registered as v{} with a wait node, which that runtime completes as "
                                    + "soon as an execution starts: each call is recorded by its events only. Upgrade "
                                    + "the runtime to hold each call's execution open until the call ends.",
                            spec.workflowId(), RecordedCallWorkflow.LEGACY_VERSION);
                    client.createWorkflow(spec.legacyIr());
                    registeredVersion = RecordedCallWorkflow.LEGACY_VERSION;
                    legacySince = Instant.now();
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
     * {@value RecordedCallWorkflow#VERSION} workflow, waits until its node is
     * claimed, here or by another instance.
     *
     * @return the execution id
     * @throws JamjetClientException     when the runtime cannot start the execution or hand out its node
     *                                   (a started execution is then cancelled)
     * @throws JamjetGovernanceException when the runtime blocked the call's node or holds it for approval
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
                legacySince = null;
            }
            version = register();
            started = client.startExecution(spec.workflowId(), version, input);
        }
        if (started == null || started.executionId() == null || started.executionId().isBlank()) {
            throw new JamjetClientException("the runtime returned no execution_id", "/executions");
        }
        String executionId = started.executionId();
        if (!RecordedCallWorkflow.VERSION.equals(version)) {
            return executionId;
        }
        if (spec.startOutcome() == null) {
            running.add(executionId);
            RUNNING_HERE.put(keyPrefix + executionId, this);
        }
        try {
            awaitNode(executionId);
        } catch (RuntimeException e) {
            release(executionId);
            cancelQuietly(executionId);
            throw e;
        }
        return executionId;
    }

    /**
     * Settles the call's node with {@code outcome}: completes it, fails it when
     * {@code outcome.status} is {@code failed}, and cancels the execution when it
     * is {@code cancelled}. Call it after the outcome event was appended. Never
     * throws: when the node is held elsewhere, its holder settles it from that event.
     */
    public void finish(String executionId, Map<String, Object> outcome) {
        if (executionId == null || spec.startOutcome() != null) {
            return;
        }
        Map<String, Object> result = outcome == null ? Map.of() : outcome;
        running.remove(executionId);
        RUNNING_HERE.remove(keyPrefix + executionId, this);
        Held h = held.remove(executionId);
        if ("cancelled".equals(result.get("status"))) {
            // The caller gave up on the call: its execution is cancelled, not completed.
            if (h != null || RecordedCallWorkflow.VERSION.equals(registeredVersion)) {
                cancelQuietly(executionId);
            }
            return;
        }
        if (h == null) {
            log.debug("Execution {}: its node is not held here; it is settled from the {} event",
                    executionId, spec.completedEvent());
            return;
        }
        settle(h, result);
    }

    /** Executions whose node this instance holds (for tests and metrics). */
    Set<String> heldExecutions() {
        return Set.copyOf(held.keySet());
    }

    /**
     * Stops renewing leases. For a few seconds first, nodes held for calls in
     * other instances are settled as their outcomes arrive; any node still held
     * after that has its lease lapse, and the runtime fails that execution.
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
        Instant deadline = Instant.now().plus(DRAIN);
        while (true) {
            boolean waiting = false;
            for (Held h : held.values()) {
                String executionId = h.item.executionId();
                if (running.contains(executionId)) {
                    continue;
                }
                try {
                    Map<String, Object> outcome = h.outcome != null ? h.outcome : reportedOutcome(executionId);
                    if (outcome == null) {
                        waiting = true;
                    } else if (held.remove(executionId, h)) {
                        settle(h, outcome);
                    }
                } catch (JamjetClientException e) {
                    log.debug("Execution {}: could not settle its node on close: {}", executionId, e.getMessage());
                }
            }
            if (!waiting || !Instant.now().isBefore(deadline) || !pause(250)) {
                break;
            }
        }
    }

    // ── Claiming ──────────────────────────────────────────────────────────────

    /** What the runtime has recorded about an execution's node. */
    private enum Verdict { UNDECIDED, HELD_ELSEWHERE, SETTLED, BLOCKED, AWAITING_APPROVAL, ENDED }

    private record NodeState(Verdict verdict, String reason) {}

    /**
     * Claim until this execution's node is accounted for: claimed here, held by
     * another instance, or stopped by the runtime. A claim error propagates.
     */
    private void awaitNode(String executionId) {
        Instant deadline = Instant.now().plus(claimWait);
        long pauseMillis = 25;
        while (true) {
            if (held.containsKey(executionId)) {
                return;
            }
            ClaimedWorkItem item = client.claimWorkItem(workerId, List.of(spec.queue()), lease, requestTimeout());
            if (item != null) {
                boolean mine = executionId.equals(item.executionId());
                take(item);
                if (mine) {
                    return;
                }
                continue;
            }
            NodeState state = nodeState(executionId);
            switch (state.verdict()) {
                case HELD_ELSEWHERE, SETTLED -> {
                    return;
                }
                case BLOCKED, ENDED -> throw new JamjetGovernanceException("JamJet runtime refused this call: "
                        + "it ended execution " + executionId + " before the call ran"
                        + (state.reason() == null ? "" : ": " + state.reason()), null);
                case AWAITING_APPROVAL -> throw new JamjetGovernanceException("JamJet runtime refused this call: "
                        + "it holds node " + spec.nodeId() + " of execution " + executionId
                        + " for approval under its policy, and this application does not wait for approvals the "
                        + "runtime holds; the execution was cancelled", null);
                case UNDECIDED -> {
                    if (!Instant.now().isBefore(deadline)) {
                        throw new JamjetClientException("the runtime did not hand out the node of execution "
                                + executionId + " within " + claimWait + " (no instance claimed it and the runtime "
                                + "recorded no decision for it)", "/work-items/claim");
                    }
                    if (!pause(pauseMillis)) {
                        throw new JamjetClientException("interrupted waiting for the node of execution "
                                + executionId, "/work-items/claim");
                    }
                    pauseMillis = Math.min(pauseMillis * 2, 500);
                }
            }
        }
    }

    /** A claimed item: settled at once for a record that outlives calls, otherwise held for its call. */
    private void take(ClaimedWorkItem item) {
        if (spec.startOutcome() != null) {
            settle(new Held(item, workerId, lease), spec.startOutcome());
            return;
        }
        RecordedCalls owner = RUNNING_HERE.get(keyPrefix + item.executionId());
        RecordedCalls target = owner != null && !owner.closed ? owner : this;
        target.held.put(item.executionId(), new Held(item, workerId, lease));
        target.ensureSweeper();
        if (target == this && !running.contains(item.executionId())) {
            // Tell the call's owner its node was handed out, so it can go ahead.
            log.debug("Holding the node of execution {}, a call this instance does not run, until its {} event",
                    item.executionId(), spec.completedEvent());
            try {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("worker_id", workerId);
                payload.put("work_item_id", item.id());
                client.sendExternalEvent(item.executionId(), HELD_EVENT, payload);
            } catch (JamjetClientException e) {
                log.warn("Execution {}: could not record that this instance holds its node ({}); its call will "
                        + "time out waiting for it", item.executionId(), e.getMessage());
            }
        }
    }

    /** What the execution's events say about its node. */
    private NodeState nodeState(String executionId) {
        boolean heldElsewhere = false;
        boolean settled = false;
        boolean approval = false;
        boolean ended = false;
        String blocked = null;
        for (ExecutionEvent e : client.listEvents(executionId, requestTimeout())) {
            String type = e.type();
            if (type == null) {
                continue;
            }
            boolean ours = spec.nodeId().equals(e.nodeId());
            switch (type) {
                case "external_event_received" -> heldElsewhere |= e.isExternalEvent(HELD_EVENT);
                case "node_completed" -> settled |= ours;
                case "tool_approval_required" -> approval |= ours;
                case "policy_violation" -> {
                    if (ours && "blocked".equals(e.field("decision"))) {
                        blocked = "policy blocked: " + e.field("rule");
                    }
                }
                case "node_failed" -> {
                    if (ours) {
                        blocked = String.valueOf(e.field("error"));
                    }
                }
                case "workflow_failed", "workflow_cancelled", "workflow_completed" -> ended = true;
                default -> { }
            }
        }
        if (blocked != null) {
            return new NodeState(Verdict.BLOCKED, blocked);
        }
        if (approval) {
            return new NodeState(Verdict.AWAITING_APPROVAL, null);
        }
        if (settled) {
            return new NodeState(Verdict.SETTLED, null);
        }
        if (heldElsewhere) {
            return new NodeState(Verdict.HELD_ELSEWHERE, null);
        }
        return new NodeState(ended ? Verdict.ENDED : Verdict.UNDECIDED, null);
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

    private Duration requestTimeout() {
        Duration sixth = lease.dividedBy(6);
        return sixth.compareTo(MAX_REQUEST) < 0 ? sixth : MAX_REQUEST;
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    /**
     * One round of lease upkeep as of {@code now}. Each node is kept on its own
     * virtual thread, so one slow request cannot make the other leases lapse.
     */
    void sweep(Instant now) {
        List<Held> all = new ArrayList<>(held.values());
        if (all.size() == 1) {
            upkeep(all.get(0), now);
        } else if (!all.isEmpty()) {
            try (ExecutorService each = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Held h : all) {
                    each.submit(() -> upkeep(h, now));
                }
            }
        }
        stopSweeperIfIdle();
    }

    private void upkeep(Held h, Instant now) {
        String executionId = h.item.executionId();
        try {
            if (now.isAfter(h.renewedAt.plus(h.lease))) {
                // Not renewed in time (the runtime was unreachable): the runtime has
                // failed the node by now, so the fence is no longer ours.
                held.remove(executionId, h);
                log.warn("Execution {}: the lease on its node lapsed before the call's outcome was settled",
                        executionId);
                return;
            }
            if (running.contains(executionId) && now.isAfter(h.claimedAt.plus(unreportedAfter))) {
                // A call that never finished (its thread died without reporting):
                // stop renewing, so the lease lapses and the runtime fails it.
                release(executionId);
                log.warn("Execution {}: the call has run for more than {} without finishing; its node is released "
                        + "and the runtime will fail the execution when the lease lapses", executionId,
                        unreportedAfter);
                return;
            }
            if (now.isAfter(h.renewedAt.plus(h.lease.dividedBy(3)))) {
                WorkItemHeartbeat beat = client.heartbeatWorkItem(h.item.id(), h.claimedBy, h.fence, requestTimeout());
                h.renewedAt = now;
                if (beat != null && beat.leaseFence() > 0) {
                    h.fence = beat.leaseFence();
                }
                if (beat != null && beat.cancelRequested()) {
                    held.remove(executionId, h);
                    log.info("Execution {} ended on the runtime (cancelled?) while its node was held", executionId);
                    return;
                }
            }
            if (h.outcome != null) {
                if (now.isAfter(h.checkedAt.plus(RECHECK)) && held.remove(executionId, h)) {
                    settle(h, h.outcome);
                }
                return;
            }
            Duration every = now.isAfter(h.claimedAt.plusSeconds(60)) ? RECHECK_SLOW : RECHECK;
            if (!running.contains(executionId) && now.isAfter(h.checkedAt.plus(every))) {
                h.checkedAt = now;
                Map<String, Object> reported = reportedOutcome(executionId);
                if (reported != null) {
                    if (held.remove(executionId, h)) {
                        settle(h, reported);
                    }
                } else if (now.isAfter(h.claimedAt.plus(unreportedAfter)) && held.remove(executionId, h)) {
                    var failure = new LinkedHashMap<String, Object>();
                    failure.put("status", "failed");
                    failure.put("error", "no outcome was reported for this call within " + unreportedAfter
                            + "; the application instance that ran it may have stopped");
                    settle(h, failure);
                }
            }
        } catch (JamjetClientException e) {
            if (e.getStatusCode() == 409) {
                held.remove(executionId, h);
                log.warn("Execution {}: its node's lease is no longer held ({})", executionId, e.getRuntimeMessage());
            } else {
                log.debug("Execution {}: lease upkeep failed, retrying: {}", executionId, e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("Execution {}: unexpected failure keeping its node's lease", executionId, e);
        }
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
        for (ExecutionEvent e : client.listEvents(executionId, requestTimeout())) {
            if (e.isExternalEvent(spec.completedEvent())) {
                latest = e.payload() == null ? Map.of() : e.payload();
            }
        }
        return latest;
    }
}
