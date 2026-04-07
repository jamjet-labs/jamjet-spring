package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.Map;

/**
 * Spring AI {@link BaseAdvisor} that injects Engram memory context into every
 * ChatClient prompt.
 *
 * <p>Before the LLM call, the advisor:
 * <ol>
 *   <li>Extracts the user prompt and the current user ID (from advisor params)</li>
 *   <li>Calls {@link EngramClient#context} to assemble a token-budgeted memory block</li>
 *   <li>Prepends the memory block as a system message to the prompt</li>
 * </ol>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ChatClient client = chatClientBuilder
 *     .defaultAdvisors(engramContextAdvisor)
 *     .build();
 *
 * String response = client.prompt()
 *     .user("What should I cook for dinner?")
 *     .advisors(a -> a.param(EngramContextAdvisor.USER_ID_KEY, "alice"))
 *     .call()
 *     .content();
 * }</pre>
 *
 * <p>If the user ID is not provided on the request, the advisor falls back to
 * {@code EngramProperties#getDefaultUserId()}. If neither is set, no memory
 * context is injected and the call proceeds normally.
 *
 * <p>Memory failures are logged but do not break the LLM call — the advisor
 * degrades gracefully.
 */
public class EngramContextAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(EngramContextAdvisor.class);

    /** Advisor param key for the user identifier. */
    public static final String USER_ID_KEY = "engram.user.id";

    /** Advisor param key for the organization identifier. */
    public static final String ORG_ID_KEY = "engram.org.id";

    /** Advisor param key to override the token budget for a single call. */
    public static final String TOKEN_BUDGET_KEY = "engram.token.budget";

    private final EngramClient client;
    private final EngramProperties properties;
    private final int defaultTokenBudget;

    public EngramContextAdvisor(EngramClient client, EngramProperties properties) {
        this(client, properties, 1000);
    }

    public EngramContextAdvisor(
            EngramClient client, EngramProperties properties, int defaultTokenBudget) {
        this.client = client;
        this.properties = properties;
        this.defaultTokenBudget = defaultTokenBudget;
    }

    @Override
    public String getName() {
        return "EngramContextAdvisor";
    }

    @Override
    public int getOrder() {
        // Run early so memory is available to downstream advisors
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userId = resolveParam(request, USER_ID_KEY, properties.getDefaultUserId());
        if (userId == null || userId.isBlank()) {
            // No user identity — can't fetch scoped memory
            return request;
        }

        String orgId = resolveParam(request, ORG_ID_KEY, properties.getDefaultOrgId());
        int tokenBudget = resolveTokenBudget(request);

        String query = extractUserPromptText(request);
        if (query == null || query.isBlank()) {
            return request;
        }

        try {
            Map<String, Object> context = client.context(
                    query, userId, orgId, tokenBudget, "system_prompt");

            String memoryText = (String) context.get("text");
            if (memoryText == null || memoryText.isBlank()) {
                return request;
            }

            Integer factsIncluded = toInt(context.get("facts_included"));
            log.debug("Injected Engram context: {} facts, {} tokens for user {}",
                    factsIncluded, context.get("token_count"), userId);

            return injectMemoryMessage(request, memoryText);

        } catch (Exception e) {
            log.warn("Engram context lookup failed, proceeding without memory: {}", e.getMessage());
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveParam(ChatClientRequest request, String key, String fallback) {
        Object value = request.context().get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }

    private int resolveTokenBudget(ChatClientRequest request) {
        Object value = request.context().get(TOKEN_BUDGET_KEY);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return defaultTokenBudget;
    }

    private String extractUserPromptText(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return null;
        }
        String contents = prompt.getContents();
        return contents == null ? null : contents;
    }

    private ChatClientRequest injectMemoryMessage(ChatClientRequest request, String memoryText) {
        Prompt original = request.prompt();
        if (original == null) {
            return request;
        }

        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(memoryText));
        messages.addAll(original.getInstructions());

        Prompt enriched = new Prompt(messages, original.getOptions());
        return ChatClientRequest.builder()
                .prompt(enriched)
                .context(request.context())
                .build();
    }

    private static Integer toInt(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        return null;
    }
}
