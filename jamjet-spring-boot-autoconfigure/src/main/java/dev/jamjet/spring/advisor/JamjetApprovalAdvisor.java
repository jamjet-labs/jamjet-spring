package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.approval.ApprovalWaitRegistry;
import dev.jamjet.spring.approval.ApprovalContent;
import dev.jamjet.spring.approval.CurrentPrincipal;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.core.Ordered;

import org.springframework.boot.convert.DurationStyle;

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

    /**
     * Context key carrying the principal that triggered the call, captured on the
     * caller's thread (or from the Reactor context) before any thread hop. Only a
     * value JamJet captured counts; a caller-supplied param here is ignored.
     */
    public static final String INITIATOR_KEY = "jamjet.approval.initiator";

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
        // Fail at startup on a bad timeout, not on the first call that needs approval.
        parseTimeout(properties.getApproval().getTimeout());
        checkDefaultDecision(properties.getApproval().getDefaultDecision());
    }

    /**
     * A timeout never approves. {@code default-decision} is deprecated and only
     * {@code rejected} is accepted, so a misconfiguration fails at startup instead
     * of letting unattended calls through.
     */
    @SuppressWarnings("deprecation")
    static void checkDefaultDecision(String defaultDecision) {
        if (defaultDecision != null && !defaultDecision.isBlank() && !"rejected".equalsIgnoreCase(defaultDecision)) {
            throw new IllegalArgumentException("spring.jamjet.approval.default-decision='" + defaultDecision
                    + "' is not supported: a call that times out never approves, so it is always rejected. "
                    + "Remove the property (it is deprecated) or set it to rejected.");
        }
    }

    /**
     * How long a call whose timeout fired keeps waiting for a decision that was
     * already being recorded: longer than the client can take to record it
     * (connect plus read timeout), plus a margin.
     */
    public static Duration decidingGrace(JamjetProperties properties) {
        return Duration.ofSeconds(properties.getConnectTimeoutSeconds())
                .plusSeconds(properties.getReadTimeoutSeconds())
                .plusSeconds(10);
    }

    @Override
    public String getName() {
        return "JamjetApprovalAdvisor";
    }

    @Override
    public int getOrder() {
        // Last before the model call, so no advisor can rewrite the prompt after
        // the reviewer approved its digest.
        return Ordered.LOWEST_PRECEDENCE - 1;
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

        String initiator = initiatorOf(request.context());
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("advisor", getName());
            payload.put("timeout", properties.getApproval().getTimeout());
            if (initiator != null) {
                payload.put("initiator", initiator);
            }
            payload.put("initiator_known", initiator != null);
            client.sendExternalEvent(executionId, REQUESTED_EVENT, payload);
        } catch (JamjetClientException e) {
            RuntimeErrors.handle(properties, log, "record the approval request for execution " + executionId, e);
        }

        log.info("Execution {} paused, waiting for human approval", executionId);

        try {
            Duration timeout = parseTimeout(properties.getApproval().getTimeout());
            // Who triggered the call, if Spring Security knows: that principal
            // cannot approve it (separation of duties, enforced by the controller).
            var context = new ApprovalWaitRegistry.WaitContext(initiator,
                    ApprovalContent.preview(effectivePrompt(request)), ApprovalContent.digest(effectivePrompt(request)));
            ApprovalDecision decision;
            try {
                decision = waitRegistry.waitForApproval(executionId, timeout, context, decidingGrace(properties));
            } catch (IllegalStateException alreadyWaiting) {
                throw new ApprovalRejectedException(executionId, alreadyWaiting.getMessage());
            }

            // Only an explicit "approved" lets the call through.
            if (decision == null || !decision.isApproved()) {
                throw new ApprovalRejectedException(executionId,
                        decision == null ? "no decision" : decision.comment());
            }

            log.info("Execution {} approved by {}", executionId,
                    decision.userId() != null ? decision.userId() : "unknown");
            return request;

        } catch (TimeoutException e) {
            log.warn("Approval timed out for execution {}; rejecting the call", executionId);
            throw new ApprovalRejectedException(executionId, "Approval timed out; a timed-out call is rejected");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApprovalRejectedException(executionId, "approval wait interrupted");
        }
    }

    /**
     * Streams: the default {@code adviseStream} runs {@link #before} after a hop
     * to another thread, where the caller's SecurityContext is gone. Capture the
     * initiator here, on the subscribing thread or from the Reactor context.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!requiresApproval(request)) {
            return BaseAdvisor.super.adviseStream(request, chain);
        }
        // Spring AI 1.0's stream path does not append output-format text; mark the
        // request so before() digests exactly what the stream sends.
        var streaming = request.mutate().context(STREAMING_KEY, Streaming.INSTANCE).build();
        if (request.context().get(INITIATOR_KEY) instanceof CapturedInitiator) {
            return BaseAdvisor.super.adviseStream(streaming, chain);
        }
        String onThisThread = CurrentPrincipal.name();
        return CurrentPrincipal.reactiveName().defaultIfEmpty("")
                .flatMapMany(reactive -> BaseAdvisor.super.adviseStream(
                        withInitiator(streaming, onThisThread != null ? onThisThread
                                : reactive.isEmpty() ? null : reactive), chain));
    }

    /** Private marker: the request is on the stream path. */
    private enum Streaming { INSTANCE }

    private static final String STREAMING_KEY = "jamjet.approval.streaming";

    /**
     * The prompt the model will actually receive. On the call path Spring AI 1.0's
     * ChatModelCallAdvisor, which runs after every advisor, appends the
     * {@code spring.ai.chat.client.output.format} context value (set by
     * {@code .entity()}, or by any param) to the user message, separated by a line
     * separator. Apply the same here so the reviewer approves that text too.
     */
    static Prompt effectivePrompt(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        if (prompt == null || request.context().get(STREAMING_KEY) == Streaming.INSTANCE) {
            return prompt;
        }
        Object format = request.context().get(ChatClientAttributes.OUTPUT_FORMAT.getKey());
        if (format instanceof String f && StringUtils.hasText(f)) {
            return prompt.augmentUserMessage(user -> user.mutate()
                    .text(user.getText() + System.lineSeparator() + f).build());
        }
        return prompt;
    }
    /**
     * The initiator JamJet captured, or the current thread's principal when none
     * was captured. Anything else under {@link #INITIATOR_KEY} was set by the caller
     * and is ignored: only the security context names the initiator.
     */
    static String initiatorOf(java.util.Map<String, Object> context) {
        Object captured = context == null ? null : context.get(INITIATOR_KEY);
        if (captured instanceof CapturedInitiator c) {
            return c.name();
        }
        if (captured != null) {
            log.warn("Ignoring a caller-supplied {} param: the initiator is taken from the security context only",
                    INITIATOR_KEY);
        }
        return CurrentPrincipal.name();
    }

    /**
     * The request carrying the captured initiator, always overwriting whatever was
     * under {@link #INITIATOR_KEY}. {@code initiator} null records it as unknown.
     */
    static ChatClientRequest withInitiator(ChatClientRequest request, String initiator) {
        if (request.context().get(INITIATOR_KEY) != null
                && !(request.context().get(INITIATOR_KEY) instanceof CapturedInitiator)) {
            log.warn("Ignoring a caller-supplied {} param: the initiator is taken from the security context only",
                    INITIATOR_KEY);
        }
        var context = new java.util.LinkedHashMap<>(request.context());
        context.put(INITIATOR_KEY, new CapturedInitiator(initiator));
        return request.mutate().context(context).build();
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

    /**
     * Parses {@code spring.jamjet.approval.timeout}. A bare number is minutes (the
     * 0.1.0 meaning); anything else is a Spring Boot duration: {@code 500ms},
     * {@code 30s}, {@code 30m}, {@code 2h}, {@code 1d}, or ISO-8601 ({@code PT30M}).
     */
    static Duration parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return Duration.ofMinutes(30);
        }
        String t = timeout.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            return Duration.ofMinutes(Long.parseLong(t));
        }
        try {
            return DurationStyle.detectAndParse(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("spring.jamjet.approval.timeout '" + timeout + "' is not a duration "
                    + "(use for example 500ms, 30s, 30m, 2h or PT30M)", e);
        }
    }
}
