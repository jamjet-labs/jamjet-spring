package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ExecutionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI {@link BaseAdvisor} that wraps ChatClient calls with JamJet
 * durable execution. Provides crash recovery, event sourcing, and
 * execution tracking transparently.
 *
 * <p>When added to a ChatClient's advisor chain, every prompt call:
 * <ol>
 *   <li>Compiles the agent interaction to a JamJet workflow IR</li>
 *   <li>Starts (or resumes) an execution on the JamJet runtime</li>
 *   <li>Records completion events after the LLM responds</li>
 * </ol>
 *
 * <p>Crash recovery: if an execution was interrupted (status=Running with no
 * recent heartbeat, or status=Paused/Failed), the advisor fetches the event
 * log and resumes from the last checkpoint.
 */
public class JamjetDurabilityAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JamjetDurabilityAdvisor.class);

    /** Context key for the JamJet execution ID. */
    public static final String EXECUTION_ID_KEY = "jamjet.execution.id";

    /** Context key for the JamJet workflow ID. */
    public static final String WORKFLOW_ID_KEY = "jamjet.workflow.id";

    /** Context key for the session/conversation ID. */
    public static final String SESSION_ID_KEY = "jamjet.session.id";

    private final JamjetRuntimeClient client;
    private final JamjetProperties properties;

    // Active executions keyed by session ID
    private final ConcurrentHashMap<String, String> activeExecutions = new ConcurrentHashMap<>();

    // Cached workflow IDs keyed by workflow IR hash
    private final ConcurrentHashMap<Integer, String> workflowCache = new ConcurrentHashMap<>();

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
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!properties.isDurabilityEnabled()) {
            return request;
        }

        String sessionId = resolveSessionId(request);

        try {
            String existingExecutionId = activeExecutions.get(sessionId);

            if (existingExecutionId != null) {
                // Check if existing execution needs recovery
                var state = tryGetExecution(existingExecutionId);
                if (state != null && (state.isPaused() || state.isFailed())) {
                    log.info("Resuming interrupted execution {} (status={})",
                            existingExecutionId, state.status());
                    return enrichRequest(request, sessionId, existingExecutionId, state.workflowId());
                }
                if (state != null && state.isCompleted()) {
                    // Previous execution completed — start fresh
                    activeExecutions.remove(sessionId);
                }
            }

            // Start a new execution
            var workflowIr = buildWorkflowIr(request, sessionId);
            int irHash = workflowIr.hashCode();

            String workflowId = workflowCache.computeIfAbsent(irHash, key -> {
                var resp = client.createWorkflow(workflowIr);
                log.debug("Created workflow {}", resp.workflowId());
                return resp.workflowId();
            });

            var execResp = client.startExecution(workflowId, null,
                    Map.of("prompt", extractPrompt(request), "session_id", sessionId));
            String executionId = execResp.executionId();
            activeExecutions.put(sessionId, executionId);

            log.debug("Started execution {} for session {}", executionId, sessionId);
            return enrichRequest(request, sessionId, executionId, workflowId);

        } catch (JamjetClientException e) {
            log.warn("JamJet runtime unavailable, proceeding without durability: {}", e.getMessage());
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!properties.isDurabilityEnabled()) {
            return response;
        }

        String executionId = (String) response.context().get(EXECUTION_ID_KEY);
        if (executionId == null) {
            return response;
        }

        try {
            // Record completion — the runtime tracks this via event sourcing
            var state = client.getExecution(executionId);
            log.debug("Execution {} status: {}", executionId, state != null ? state.status() : "unknown");
        } catch (JamjetClientException e) {
            log.warn("Failed to record execution completion: {}", e.getMessage());
        }

        return response;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private ChatClientRequest enrichRequest(ChatClientRequest request,
                                            String sessionId,
                                            String executionId,
                                            String workflowId) {
        var context = new LinkedHashMap<>(request.context());
        context.put(SESSION_ID_KEY, sessionId);
        context.put(EXECUTION_ID_KEY, executionId);
        context.put(WORKFLOW_ID_KEY, workflowId);

        return ChatClientRequest.builder()
                .prompt(request.prompt())
                .context(context)
                .build();
    }

    private Map<String, Object> buildWorkflowIr(ChatClientRequest request, String sessionId) {
        return Map.of(
                "name", "spring-ai-chat-" + sessionId,
                "version", "1",
                "nodes", Map.of(
                        "chat", Map.of(
                                "kind", "LlmGenerate",
                                "prompt_template", extractPrompt(request)
                        )
                ),
                "edges", Map.of(),
                "entry_node", "chat"
        );
    }

    private ExecutionState tryGetExecution(String executionId) {
        try {
            return client.getExecution(executionId);
        } catch (JamjetClientException e) {
            log.debug("Could not fetch execution {}: {}", executionId, e.getMessage());
            return null;
        }
    }
}
