package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI {@link BaseAdvisor} that appends each ChatClient prompt and response
 * to the execution the {@link JamjetDurabilityAdvisor} started, as
 * {@code spring_ai.prompt} and {@code spring_ai.response} external events
 * ({@code POST /executions/{id}/external-event}). Read them back with
 * {@code GET /executions/{id}/events}.
 *
 * <p>Configurable via:
 * <ul>
 *   <li>{@code spring.jamjet.audit.include-prompts}: record the prompt text (default: true)</li>
 *   <li>{@code spring.jamjet.audit.include-responses}: record the response text and
 *       token usage (default: true)</li>
 * </ul>
 *
 * <p>If an event cannot be recorded the call is refused with a
 * {@link dev.jamjet.spring.JamjetGovernanceException} unless
 * {@code spring.jamjet.on-runtime-error=fail-open}. A refused prompt never
 * reaches the model.
 */
public class JamjetAuditAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JamjetAuditAdvisor.class);

    /** Correlation key of the prompt event. */
    public static final String PROMPT_EVENT = "spring_ai.prompt";

    /** Correlation key of the response event. */
    public static final String RESPONSE_EVENT = "spring_ai.response";

    private final JamjetRuntimeClient client;
    private final JamjetProperties properties;

    public JamjetAuditAdvisor(JamjetRuntimeClient client, JamjetProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "JamjetAuditAdvisor";
    }

    @Override
    public int getOrder() {
        // After the durability advisor (-100), so the execution id is in context.
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String executionId = executionIdOf(request.context());
        if (executionId == null) {
            return request;
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("advisor", getName());
        if (properties.getAudit().isIncludePrompts() && request.prompt() != null) {
            String contents = request.prompt().getContents();
            if (contents != null) {
                payload.put("content", contents);
            }
        }
        record(executionId, PROMPT_EVENT, payload);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String executionId = executionIdOf(response.context());
        if (executionId == null) {
            return response;
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("advisor", getName());
        if (properties.getAudit().isIncludeResponses() && response.chatResponse() != null) {
            var result = response.chatResponse().getResult();
            if (result != null && result.getOutput() != null && result.getOutput().getText() != null) {
                payload.put("content", result.getOutput().getText());
            }
            var metadata = response.chatResponse().getMetadata();
            if (metadata != null && metadata.getUsage() != null) {
                var usage = metadata.getUsage();
                putIfNotNull(payload, "prompt_tokens", usage.getPromptTokens());
                putIfNotNull(payload, "completion_tokens", usage.getCompletionTokens());
                putIfNotNull(payload, "total_tokens", usage.getTotalTokens());
            }
        }
        recordAfterCall(executionId, payload);
        return response;
    }

    /**
     * Streaming: records the prompt before the model runs and the whole
     * streamed text once the stream completes (not only its last chunk).
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Mono.fromCallable(() -> before(request, chain))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(advised -> {
                    String executionId = executionIdOf(advised.context());
                    if (executionId == null) {
                        return chain.nextStream(advised);
                    }
                    var text = new StringBuffer();
                    return chain.nextStream(advised)
                            .doOnNext(r -> text.append(JamjetDurabilityAdvisor.textOf(r)))
                            .concatWith(Mono.<ChatClientResponse>fromRunnable(() -> {
                                        var payload = new LinkedHashMap<String, Object>();
                                        payload.put("advisor", getName());
                                        payload.put("streamed", true);
                                        if (properties.getAudit().isIncludeResponses()) {
                                            payload.put("content", text.toString());
                                        }
                                        recordAfterCall(executionId, payload);
                                    })
                                    .subscribeOn(Schedulers.boundedElastic()));
                });
    }

    private void recordAfterCall(String executionId, Map<String, Object> payload) {
        try {
            client.sendExternalEvent(executionId, RESPONSE_EVENT, payload);
        } catch (JamjetClientException e) {
            RuntimeErrors.afterCall(properties, log, "record " + RESPONSE_EVENT + " for execution " + executionId, e);
        }
    }

    private void record(String executionId, String key, Map<String, Object> payload) {
        try {
            client.sendExternalEvent(executionId, key, payload);
            log.debug("Audit: recorded {} for execution {}", key, executionId);
        } catch (JamjetClientException e) {
            RuntimeErrors.handle(properties, log, "record " + key + " for execution " + executionId, e);
        }
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String executionIdOf(Map<String, Object> context) {
        Object id = context == null ? null : context.get(JamjetDurabilityAdvisor.EXECUTION_ID_KEY);
        return id instanceof String s && !s.isBlank() ? s : null;
    }
}
