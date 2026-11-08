package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCallWorkflow;
import dev.jamjet.spring.client.RecordedCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import dev.jamjet.spring.JamjetGovernanceException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spring AI {@link BaseAdvisor} that records every ChatClient call as an
 * execution on the JamJet runtime.
 *
 * <p>For each call the advisor:
 * <ol>
 *   <li>registers the {@code spring-ai-chat} workflow once (the IR is constant,
 *       so re-registering after a restart is a no-op on the runtime);</li>
 *   <li>starts an execution of it ({@code POST /executions}) before the model is
 *       called, claims the execution's node, and puts the execution id in the
 *       advisor context under {@link #EXECUTION_ID_KEY} for the audit and approval
 *       advisors;</li>
 *   <li>after the model answers, or throws, appends a
 *       {@code spring_ai.completed} external event with the outcome, then
 *       completes the node with it (or fails it, when the call failed).</li>
 * </ol>
 *
 * <p>The model call runs in this JVM, not on the runtime. The workflow's single
 * node is an {@code external_fn} node on the {@code <workflow-id>.calls} queue,
 * which this advisor claims and holds while the call runs
 * ({@link RecordedCalls}): the execution is {@code running} until the call ends,
 * and the runtime fails it if this process stops mid-call. The runtime does not
 * re-run or replay Spring AI calls. On a runtime that predates {@code external_fn}
 * nodes (0.5.x) the workflow is registered with a {@code wait} node instead,
 * which that runtime completes at once; the events are the record.
 *
 * <p>If the runtime cannot record the call, the call is refused with a
 * {@link dev.jamjet.spring.JamjetGovernanceException} unless
 * {@code spring.jamjet.on-runtime-error=fail-open}.
 */
public class JamjetDurabilityAdvisor implements BaseAdvisor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JamjetDurabilityAdvisor.class);

    /** Context key for the JamJet execution ID. */
    public static final String EXECUTION_ID_KEY = "jamjet.execution.id";

    /** Context key for the JamJet workflow ID. */
    public static final String WORKFLOW_ID_KEY = "jamjet.workflow.id";

    /** Context key for the session/conversation ID. */
    public static final String SESSION_ID_KEY = "jamjet.session.id";

    /**
     * Version of the registered workflow. Bump it if {@link #workflowIr} ever
     * changes. A runtime without {@code external_fn} nodes gets
     * {@link RecordedCallWorkflow#LEGACY_VERSION} instead.
     */
    public static final String WORKFLOW_VERSION = RecordedCallWorkflow.VERSION;

    /** The workflow's only node. */
    public static final String NODE_ID = "chat";

    /** Correlation key of the outcome event appended after each call. */
    public static final String COMPLETED_EVENT = "spring_ai.completed";

    private final JamjetRuntimeClient client;
    private final JamjetProperties properties;
    private final RecordedCalls calls;

    public JamjetDurabilityAdvisor(JamjetRuntimeClient client, JamjetProperties properties) {
        this.client = client;
        this.properties = properties;
        this.calls = new RecordedCalls(client, spec(properties.getWorkflowId()), RecordedCalls.DEFAULT_LEASE,
                unreportedAfter(properties));
    }

    private static RecordedCalls.Spec spec(String workflowId) {
        return RecordedCalls.Spec.call(workflowId, NODE_ID, COMPLETED_EVENT,
                "Spring AI ChatClient call", "jamjet-spring-boot-starter");
    }

    /**
     * How long a node held for another instance's call waits for its outcome: at
     * least an hour, and longer than a call can wait for approval.
     */
    private static Duration unreportedAfter(JamjetProperties properties) {
        Duration approval;
        try {
            approval = JamjetApprovalAdvisor.parseTimeout(properties.getApproval().getTimeout());
        } catch (IllegalArgumentException e) {
            approval = Duration.ZERO;
        }
        Duration atLeast = approval.plusMinutes(30);
        return atLeast.compareTo(RecordedCalls.DEFAULT_UNREPORTED_AFTER) > 0
                ? atLeast : RecordedCalls.DEFAULT_UNREPORTED_AFTER;
    }

    /**
     * The workflow version this advisor registered: {@link #WORKFLOW_VERSION}, or
     * {@link RecordedCallWorkflow#LEGACY_VERSION} on a runtime without
     * {@code external_fn} nodes. {@code null} before the first call.
     */
    public String registeredWorkflowVersion() {
        return calls.registeredVersion();
    }

    /** Stops renewing the leases this advisor holds. */
    @Override
    public void close() {
        calls.close();
    }

    @Override
    public String getName() {
        return "JamjetDurabilityAdvisor";
    }

    /**
     * Order of this advisor: before Spring AI's {@code ToolCallingAdvisor} at its
     * default order, so a call whose model asks for tools is one execution. The
     * tool-calling advisor runs the rest of the chain once per model round; the
     * audit and approval advisors run inside that loop and see every round.
     */
    public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 50;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest advised = before(request, chain);
        String executionId = executionIdOf(advised.context());
        ChatClientResponse response;
        try {
            response = chain.nextCall(advised);
        } catch (RuntimeException | Error e) {
            recordModelFailure(executionId, e);
            throw e;
        } finally {
            closeApproval(advised);
        }
        recordOutcome(executionId, success(response));
        return response;
    }

    /**
     * Streaming. In fail-closed mode a stream is refused before the model runs
     * unless {@code spring.jamjet.allow-streaming=true}, because its record can
     * only be written after its output was delivered. When it runs, the outcome
     * is recorded on completion, error and cancel.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!properties.isDurabilityEnabled()) {
            return chain.nextStream(request);
        }
        if (!properties.isFailOpen() && !properties.isAllowStreaming()) {
            return Flux.error(new JamjetGovernanceException("Streaming ChatClient calls are refused in "
                    + "fail-closed mode because their record can only be written after the output was "
                    + "delivered; set spring.jamjet.allow-streaming=true to allow them", null));
        }
        return Mono.fromCallable(() -> before(request, chain))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(advised -> {
                    String executionId = executionIdOf(advised.context());
                    var chars = new AtomicLong();
                    var settled = new AtomicBoolean();
                    return chain.nextStream(advised)
                            .doFinally(signal -> closeApproval(advised))
                            .doOnNext(r -> chars.addAndGet(textOf(r).length()))
                            .onErrorResume(err -> Mono.<ChatClientResponse>fromRunnable(() -> {
                                        if (settled.compareAndSet(false, true)) {
                                            recordModelFailure(executionId, err);
                                        }
                                    })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then(Mono.error(err)))
                            .concatWith(Mono.<ChatClientResponse>fromRunnable(() -> {
                                        if (settled.compareAndSet(false, true)) {
                                            var outcome = new LinkedHashMap<String, Object>();
                                            outcome.put("status", "completed");
                                            outcome.put("streamed", true);
                                            outcome.put("chars", chars.get());
                                            recordOutcome(executionId, outcome);
                                        }
                                    })
                                    .subscribeOn(Schedulers.boundedElastic()))
                            .doOnCancel(() -> {
                                if (settled.compareAndSet(false, true) && executionId != null) {
                                    Schedulers.boundedElastic().schedule(() -> {
                                        var cancelled = Map.<String, Object>of("status", "cancelled",
                                                "streamed", true);
                                        try {
                                            client.sendExternalEvent(executionId, COMPLETED_EVENT, cancelled);
                                        } catch (JamjetClientException e) {
                                            log.warn("Could not record the cancellation of execution {}: {}",
                                                    executionId, e.getMessage());
                                        }
                                        calls.finish(executionId, cancelled);
                                    });
                                }
                            });
                });
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!properties.isDurabilityEnabled()) {
            return request;
        }
        String sessionId = resolveSessionId(request);
        try {
            var input = new LinkedHashMap<String, Object>();
            input.put("session_id", sessionId);
            if (properties.getAudit().isIncludePrompts()) {
                input.put("prompt", extractPrompt(request));
            }
            // Registers the workflow first (again if the runtime forgot it), and
            // claims the execution's node so this call holds it while it runs.
            // A runtime that refuses the call under its policy is a
            // JamjetGovernanceException, whatever on-runtime-error says.
            String executionId = calls.start(input);
            log.debug("Started execution {} for session {}", executionId, sessionId);
            return enrichRequest(request, sessionId, executionId);
        } catch (JamjetClientException e) {
            RuntimeErrors.handle(properties, log, "start an execution for this call", e);
            return request;
        }
    }

    /** Not used: {@link #adviseCall} and {@link #adviseStream} record the outcome themselves. */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    // ── Workflow IR ───────────────────────────────────────────────────────────

    /**
     * The workflow every advised call runs: one {@code external_fn} node on the
     * {@code <workflowId>.calls} queue, which the advisor claims and completes with
     * the {@code spring_ai.completed} outcome. Shape follows the runtime's
     * {@code WorkflowIr}: {@code workflow_id}, {@code version}, {@code start_node}
     * naming a node, {@code nodes} keyed by id, {@code edges} as a list.
     */
    public static Map<String, Object> workflowIr(String workflowId) {
        return spec(workflowId).ir();
    }

    /** The {@link RecordedCallWorkflow#LEGACY_VERSION} workflow, registered on runtimes without {@code external_fn}. */
    public static Map<String, Object> legacyWorkflowIr(String workflowId) {
        return spec(workflowId).legacyIr();
    }

    // ── Outcome ───────────────────────────────────────────────────────────────

    /** Record the outcome after the model returned. A failure here is reported as a recording failure. */
    private void recordOutcome(String executionId, Map<String, Object> outcome) {
        if (executionId == null) {
            return;
        }
        try {
            client.sendExternalEvent(executionId, COMPLETED_EVENT, outcome);
        } catch (JamjetClientException e) {
            calls.finish(executionId, outcome);
            RuntimeErrors.afterCall(properties, log, "record the outcome of execution " + executionId, e);
            return;
        }
        calls.finish(executionId, outcome);
    }

    /**
     * Record that the model call failed. If that cannot be written either, the
     * caller still gets the model's own error, with the recording failure
     * attached as suppressed (fail-closed) or logged (fail-open).
     */
    private void recordModelFailure(String executionId, Throwable modelError) {
        if (executionId == null) {
            return;
        }
        var failure = failure(modelError);
        try {
            client.sendExternalEvent(executionId, COMPLETED_EVENT, failure);
            calls.finish(executionId, failure);
        } catch (JamjetClientException e) {
            calls.finish(executionId, failure);
            if (properties.isFailOpen()) {
                log.warn("Could not record the failure of execution {}: {}", executionId, e.getMessage());
            } else {
                modelError.addSuppressed(RuntimeErrors.recordingFailure(
                        "record the failure of execution " + executionId, e));
            }
        }
    }

    static String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static Map<String, Object> success(ChatClientResponse response) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", "completed");
        if (response != null && response.chatResponse() != null) {
            var result = response.chatResponse().getResult();
            if (result != null && result.getMetadata() != null
                    && result.getMetadata().getFinishReason() != null) {
                payload.put("finish_reason", result.getMetadata().getFinishReason());
            }
            var metadata = response.chatResponse().getMetadata();
            if (metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()) {
                payload.put("model", metadata.getModel());
            }
        }
        return payload;
    }

    private static Map<String, Object> failure(Throwable e) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", "failed");
        payload.put("error", e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        return payload;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String executionIdOf(Map<String, Object> context) {
        Object id = context == null ? null : context.get(EXECUTION_ID_KEY);
        return id instanceof String s && !s.isBlank() ? s : null;
    }

    private String resolveSessionId(ChatClientRequest request) {
        Object sessionId = request.context().get(SESSION_ID_KEY);
        if (sessionId instanceof String s && !s.isBlank()) {
            return s;
        }
        return UUID.randomUUID().toString();
    }

    private String extractPrompt(ChatClientRequest request) {
        if (request.prompt() != null && request.prompt().getContents() != null) {
            return request.prompt().getContents();
        }
        return "";
    }

    private ChatClientRequest enrichRequest(ChatClientRequest request, String sessionId, String executionId) {
        var context = new LinkedHashMap<>(request.context());
        context.put(SESSION_ID_KEY, sessionId);
        context.put(EXECUTION_ID_KEY, executionId);
        context.put(WORKFLOW_ID_KEY, properties.getWorkflowId());
        // Always a new one: whatever the caller put under this key is replaced.
        context.put(ApprovedCall.KEY,
                new ApprovedCall(executionId, properties.getApproval().getMaxContinuedRounds()));
        return request.mutate().context(context).build();
    }

    private static void closeApproval(ChatClientRequest advised) {
        if (advised.context().get(ApprovedCall.KEY) instanceof ApprovedCall call) {
            call.close();
        }
    }
}
