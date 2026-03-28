package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI {@link BaseAdvisor} that logs every ChatClient interaction
 * to the JamJet audit trail. Records prompts, responses, tool calls,
 * and token usage as immutable audit entries.
 *
 * <p>Configurable via:
 * <ul>
 *   <li>{@code spring.jamjet.audit.include-prompts} — log full prompt text (default: true)</li>
 *   <li>{@code spring.jamjet.audit.include-responses} — log full response text (default: true)</li>
 * </ul>
 */
public class JamjetAuditAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JamjetAuditAdvisor.class);

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
        // After durability advisor (-100), before memory advisors
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String executionId = (String) request.context().get(JamjetDurabilityAdvisor.EXECUTION_ID_KEY);
        if (executionId == null) {
            return request;
        }

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "prompt");
            payload.put("advisor", getName());

            if (properties.getAudit().isIncludePrompts() && request.prompt() != null) {
                String contents = request.prompt().getContents();
                if (contents != null) {
                    payload.put("content", contents);
                }
            }

            client.sendExternalEvent(executionId, "audit", payload);
            log.debug("Audit: logged prompt for execution {}", executionId);
        } catch (JamjetClientException e) {
            log.warn("Audit: failed to log prompt: {}", e.getMessage());
        }

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String executionId = (String) response.context().get(JamjetDurabilityAdvisor.EXECUTION_ID_KEY);
        if (executionId == null) {
            return response;
        }

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "response");
            payload.put("advisor", getName());

            if (properties.getAudit().isIncludeResponses() && response.chatResponse() != null) {
                var result = response.chatResponse().getResult();
                if (result != null && result.getOutput() != null) {
                    payload.put("content", result.getOutput().getText());
                }
                // Include token usage if available
                var metadata = response.chatResponse().getMetadata();
                if (metadata != null && metadata.getUsage() != null) {
                    var usage = metadata.getUsage();
                    payload.put("prompt_tokens", usage.getPromptTokens());
                    payload.put("completion_tokens", usage.getCompletionTokens());
                    payload.put("total_tokens", usage.getTotalTokens());
                }
            }

            client.sendExternalEvent(executionId, "audit", payload);
            log.debug("Audit: logged response for execution {}", executionId);
        } catch (JamjetClientException e) {
            log.warn("Audit: failed to log response: {}", e.getMessage());
        }

        return response;
    }
}
