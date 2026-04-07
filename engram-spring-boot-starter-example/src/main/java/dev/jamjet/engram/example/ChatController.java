package dev.jamjet.engram.example;

import dev.jamjet.engram.EngramClient;
import dev.jamjet.engram.spring.EngramContextAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint that demonstrates the full Engram + Spring AI loop.
 *
 * <p>{@code POST /chat} accepts a user ID and a message, calls the LLM with
 * memory-augmented context, then writes the new exchange back into Engram
 * so future calls remember it.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final String SYSTEM_PROMPT =
            "You are a friendly customer support assistant. " +
                    "If the memory section below contains relevant facts about the user, " +
                    "use them to personalise your response. Be concise.";

    private final ChatClient chatClient;
    private final EngramClient memory;

    public ChatController(ChatClient.Builder chatClientBuilder, EngramClient memory) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.memory = memory;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        log.info("Chat request from user {}: {}", request.userId(), request.message());

        // Step 1: Call the LLM. The EngramContextAdvisor automatically injects
        // memory context into the prompt because we passed the user ID.
        String response = chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.param(EngramContextAdvisor.USER_ID_KEY, request.userId()))
                .call()
                .content();

        // Step 2: Persist the exchange back into Engram so future calls remember it.
        try {
            memory.add(
                    List.of(
                            Map.of("role", "user", "content", request.message()),
                            Map.of("role", "assistant", "content", response)
                    ),
                    request.userId(),
                    null,
                    null
            );
        } catch (Exception e) {
            log.warn("Failed to persist exchange to Engram: {}", e.getMessage());
        }

        return Map.of(
                "user_id", request.userId(),
                "response", response
        );
    }

    public record ChatRequest(String userId, String message) {}
}
