package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCallWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

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
 *       called, and puts the execution id in the advisor context under
 *       {@link #EXECUTION_ID_KEY} for the audit and approval advisors;</li>
 *   <li>after the model answers, or throws, appends a
 *       {@code spring_ai.completed} external event with the outcome.</li>
 * </ol>
 *
 * <p>The model call runs in this JVM, not on the runtime. The workflow's single
 * node is a {@code wait} on the {@code spring_ai.completed} external event: the
 * runtime holds the record, this advisor reports the result. The runtime does not
 * re-run or replay Spring AI calls.
 *
 * <p>If the runtime cannot record the call, the call is refused with a
 * {@link dev.jamjet.spring.JamjetGovernanceException} unless
 * {@code spring.jamjet.on-runtime-error=fail-open}.
 */
public class JamjetDurabilityAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JamjetDurabilityAdvisor.class);

    /** Context key for the JamJet execution ID. */
    public static final String EXECUTION_ID_KEY = "jamjet.execution.id";

    /** Context key for the JamJet workflow ID. */
    public static final String WORKFLOW_ID_KEY = "jamjet.workflow.id";

    /** Context key for the session/conversation ID. */
    public static final String SESSION_ID_KEY = "jamjet.session.id";

    /** Version of the registered workflow. Bump it if {@link #workflowIr} ever changes. */
    public static final String WORKFLOW_VERSION = RecordedCallWorkflow.VERSION;

    /** The workflow's only node. */
    public static final String NODE_ID = "chat";

    /** Correlation key of the outcome event appended after each call. */
    public static final String COMPLETED_EVENT = "spring_ai.completed";

    private final JamjetRuntimeClient client;
    private final JamjetProperties properties;
    private final Object registrationLock = new Object();
    private volatile boolean workflowRegistered;

    public JamjetDurabilityAdvisor(JamjetRuntimeClient client, JamjetProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "JamjetDurabilityAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest advised = before(request, chain);
        String executionId = executionIdOf(advised.context());
        ChatClientResponse response;
        try {
            response = chain.nextCall(advised);
        } catch (RuntimeException e) {
            recordOutcome(executionId, failure(e));
            throw e;
        }
        recordOutcome(executionId, success(response));
        return response;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!properties.isDurabilityEnabled()) {
            return request;
        }
        String sessionId = resolveSessionId(request);
        try {
            ensureWorkflowRegistered();
            var input = new LinkedHashMap<String, Object>();
            input.put("session_id", sessionId);
            if (properties.getAudit().isIncludePrompts()) {
                input.put("prompt", extractPrompt(request));
            }
            var started = client.startExecution(properties.getWorkflowId(), WORKFLOW_VERSION, input);
            if (started == null || started.executionId() == null || started.executionId().isBlank()) {
                throw new JamjetClientException("the runtime returned no execution_id", "/executions");
            }
            log.debug("Started execution {} for session {}", started.executionId(), sessionId);
            return enrichRequest(request, sessionId, started.executionId());
        } catch (JamjetClientException e) {
            RuntimeErrors.handle(properties, log, "start an execution for this call", e);
            return request;
        }
    }

    /** Used on the streaming path, where Spring AI calls it with the final chunk. */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (properties.isDurabilityEnabled()) {
            recordOutcome(executionIdOf(response.context()), success(response));
        }
        return response;
    }

    // ── Workflow IR ───────────────────────────────────────────────────────────

    /**
     * The workflow every advised call runs: one {@code wait} node released by the
     * {@code spring_ai.completed} external event. Shape follows the runtime's
     * {@code WorkflowIr}: {@code workflow_id}, {@code version}, {@code start_node}
     * naming a node, {@code nodes} keyed by id, {@code edges} as a list.
     */
    public static Map<String, Object> workflowIr(String workflowId) {
        return RecordedCallWorkflow.ir(workflowId, NODE_ID, COMPLETED_EVENT,
                "Spring AI ChatClient call", "jamjet-spring-boot-starter");
    }

    private void ensureWorkflowRegistered() {
        if (workflowRegistered) {
            return;
        }
        synchronized (registrationLock) {
            if (!workflowRegistered) {
                var resp = client.createWorkflow(workflowIr(properties.getWorkflowId()));
                log.debug("Registered workflow {} v{}", resp != null ? resp.workflowId() : "?",
                        resp != null ? resp.version() : "?");
                workflowRegistered = true;
            }
        }
    }

    // ── Outcome ───────────────────────────────────────────────────────────────

    private void recordOutcome(String executionId, Map<String, Object> outcome) {
        if (executionId == null) {
            return;
        }
        try {
            client.sendExternalEvent(executionId, COMPLETED_EVENT, outcome);
        } catch (JamjetClientException e) {
            RuntimeErrors.handle(properties, log, "record the outcome of execution " + executionId, e);
        }
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

    private static Map<String, Object> failure(RuntimeException e) {
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
        return request.mutate().context(context).build();
    }
}
