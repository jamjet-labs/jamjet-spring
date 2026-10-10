package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.approval.ApprovalWaitRegistry;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Spring AI {@link BaseAdvisor} that pauses execution for human approval
 * before proceeding with ChatClient calls.
 *
 * <p>When enabled, the advisor:
 * <ol>
 *   <li>Records a {@code spring_ai.approval_requested} event on the call's execution</li>
 *   <li>Blocks (with configurable timeout) until a decision is received</li>
 *   <li>On an explicit {@code "approved"}, continues the chain; on anything else, or
 *       on timeout with the default decision {@code rejected}, throws
 *       {@link ApprovalRejectedException}</li>
 * </ol>
 *
 * <p>A call that requires approval but has no execution id (durability disabled, or
 * the runtime failed open) is rejected, never run unapproved.
 *
 * <p>Approvals are received via the {@code /jamjet/approvals/{executionId}} endpoint
 * (see {@link dev.jamjet.spring.approval.JamjetApprovalController}).
 */
public class JamjetApprovalAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalAdvisor.class);

    /** Context key to explicitly require approval for a request. */
    public static final String REQUIRES_APPROVAL_KEY = "jamjet.approval.required";

    /** Correlation key of the event recording that a call is waiting for approval. */
    public static final String REQUESTED_EVENT = "spring_ai.approval_requested";

    private final JamjetRuntimeClient client;
    private final JamjetProperties properties;
    private final ApprovalWaitRegistry waitRegistry;

    public JamjetApprovalAdvisor(JamjetRuntimeClient client,
                                 JamjetProperties properties,
                                 ApprovalWaitRegistry waitRegistry) {
        this.client = client;
        this.properties = properties;
        this.waitRegistry = waitRegistry;
    }

    @Override
    public String getName() {
        return "JamjetApprovalAdvisor";
    }

    @Override
    public int getOrder() {
        // After audit (-50), before the LLM call
        return Ordered.LOWEST_PRECEDENCE - 30;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!requiresApproval(request)) {
            return request;
        }

        Object id = request.context().get(JamjetDurabilityAdvisor.EXECUTION_ID_KEY);
        if (!(id instanceof String executionId) || executionId.isBlank()) {
            // Without an execution there is nothing a reviewer can approve. Skipping
            // the gate would run a call that was marked as needing approval.
            throw new ApprovalRejectedException(null,
                    "approval is required but the call has no JamJet execution to approve against "
                            + "(is the durability advisor enabled and recording?)");
        }

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("advisor", getName());
            payload.put("timeout", properties.getApproval().getTimeout());
            client.sendExternalEvent(executionId, REQUESTED_EVENT, payload);
        } catch (JamjetClientException e) {
            RuntimeErrors.handle(properties, log, "record the approval request for execution " + executionId, e);
        }

        log.info("Execution {} paused, waiting for human approval", executionId);

        try {
            Duration timeout = parseTimeout(properties.getApproval().getTimeout());
            ApprovalDecision decision = waitRegistry.waitForApproval(executionId, timeout);

            // Only an explicit "approved" lets the call through.
            if (decision == null || !decision.isApproved()) {
                throw new ApprovalRejectedException(executionId,
                        decision == null ? "no decision" : decision.comment());
            }

            log.info("Execution {} approved by {}", executionId,
                    decision.userId() != null ? decision.userId() : "unknown");
            return request;

        } catch (TimeoutException e) {
            String defaultDecision = properties.getApproval().getDefaultDecision();
            log.warn("Approval timed out for execution {}, applying default: {}",
                    executionId, defaultDecision);

            if (!"approved".equalsIgnoreCase(defaultDecision)) {
                throw new ApprovalRejectedException(executionId,
                        "Approval timed out, default decision: " + defaultDecision);
            }
            return request;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApprovalRejectedException(executionId, "approval wait interrupted");
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private boolean requiresApproval(ChatClientRequest request) {
        return requiresApproval(request.context());
    }

    /** True when the advisor context marks the call as needing approval. */
    public static boolean requiresApproval(java.util.Map<String, Object> context) {
        Object required = context == null ? null : context.get(REQUIRES_APPROVAL_KEY);
        return Boolean.TRUE.equals(required) || (required instanceof String s && s.equalsIgnoreCase("true"));
    }

    static Duration parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return Duration.ofMinutes(30);
        }
        timeout = timeout.trim().toLowerCase();
        if (timeout.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(timeout.substring(0, timeout.length() - 1)));
        }
        if (timeout.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(timeout.substring(0, timeout.length() - 1)));
        }
        if (timeout.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(timeout.substring(0, timeout.length() - 1)));
        }
        return Duration.ofMinutes(Long.parseLong(timeout));
    }
}
