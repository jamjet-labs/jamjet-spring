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
import java.util.concurrent.TimeoutException;

/**
 * Spring AI {@link BaseAdvisor} that pauses execution for human approval
 * before proceeding with ChatClient calls.
 *
 * <p>When enabled, the advisor:
 * <ol>
 *   <li>Registers the execution as pending approval</li>
 *   <li>Optionally sends a webhook notification</li>
 *   <li>Blocks (with configurable timeout) until approval is received</li>
 *   <li>On approval → continues the chain; on rejection → throws exception</li>
 * </ol>
 *
 * <p>Approvals are received via the {@code /jamjet/approvals/{executionId}} endpoint
 * (see {@link dev.jamjet.spring.approval.JamjetApprovalController}).
 */
public class JamjetApprovalAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalAdvisor.class);

    /** Context key to explicitly require approval for a request. */
    public static final String REQUIRES_APPROVAL_KEY = "jamjet.approval.required";

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

        String executionId = (String) request.context().get(JamjetDurabilityAdvisor.EXECUTION_ID_KEY);
        if (executionId == null) {
            log.warn("Approval required but no execution ID in context — skipping");
            return request;
        }

        log.info("Execution {} paused — waiting for human approval", executionId);

        try {
            Duration timeout = parseTimeout(properties.getApproval().getTimeout());
            ApprovalDecision decision = waitRegistry.waitForApproval(executionId, timeout);

            if ("rejected".equalsIgnoreCase(decision.decision())) {
                throw new ApprovalRejectedException(executionId, decision.comment());
            }

            log.info("Execution {} approved by {}", executionId,
                    decision.userId() != null ? decision.userId() : "unknown");
            return request;

        } catch (TimeoutException e) {
            String defaultDecision = properties.getApproval().getDefaultDecision();
            log.warn("Approval timed out for execution {} — applying default: {}",
                    executionId, defaultDecision);

            if ("rejected".equalsIgnoreCase(defaultDecision)) {
                throw new ApprovalRejectedException(executionId,
                        "Approval timed out, default decision: rejected");
            }
            return request;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JamjetClientException("Approval wait interrupted for " + executionId, e);
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private boolean requiresApproval(ChatClientRequest request) {
        Object required = request.context().get(REQUIRES_APPROVAL_KEY);
        return Boolean.TRUE.equals(required) || "true".equals(required);
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
