package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ChatMemoryRepository} backed by Engram's chat message storage.
 *
 * <p>This repository stores and retrieves full chat messages (user, assistant,
 * system) in Engram's durable memory layer, making it a drop-in replacement for
 * Spring AI's {@link org.springframework.ai.chat.memory.InMemoryChatMemoryRepository}.
 *
 * <p>Unlike the {@link EngramContextAdvisor} (which injects recalled <em>facts</em>
 * as a system prompt), this repository preserves the original conversation
 * history verbatim — suitable for
 * {@link org.springframework.ai.chat.memory.MessageWindowChatMemory} and
 * other memory strategies that need full message replay.
 *
 * <h2>Usage</h2>
 *
 * <p>Auto-configured by default when Spring AI and Engram are both on the
 * classpath. Disable with:
 *
 * <pre>{@code
 * engram:
 *   spring-ai:
 *     chat-memory-repository: false
 * }</pre>
 *
 * <p>Then wire it into a {@code MessageWindowChatMemory}:
 *
 * <pre>{@code
 * @Bean
 * MessageWindowChatMemory chatMemory(ChatMemoryRepository repository) {
 *     return MessageWindowChatMemory.builder()
 *             .chatMemoryRepository(repository)
 *             .maxMessages(50)
 *             .build();
 * }
 * }</pre>
 *
 * @see EngramContextAdvisor
 * @see ChatMemoryRepository
 */
public class EngramChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(EngramChatMemoryRepository.class);

    private final EngramClient client;
    private final EngramProperties properties;

    public EngramChatMemoryRepository(EngramClient client, EngramProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<String> findConversationIds() {
        try {
            return client.listConversations(
                    properties.getDefaultUserId(), properties.getDefaultOrgId());
        } catch (Exception e) {
            log.warn("Failed to list conversations: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            List<Map<String, Object>> raw = client.getMessages(
                    conversationId, null,
                    properties.getDefaultUserId(), properties.getDefaultOrgId());
            return toMessages(raw);
        } catch (Exception e) {
            log.warn("Failed to get messages for conversation {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<Map<String, String>> raw = new ArrayList<>();
            for (Message msg : messages) {
                raw.add(Map.of(
                        "role", msg.getMessageType().getValue(),
                        "content", msg.getText()));
            }
            client.saveMessages(conversationId, raw,
                    properties.getDefaultUserId(), properties.getDefaultOrgId());
        } catch (Exception e) {
            log.error("Failed to save messages for conversation {}: {}", conversationId, e.getMessage());
            throw new RuntimeException("Engram message save failed", e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            client.deleteMessages(conversationId,
                    properties.getDefaultUserId(), properties.getDefaultOrgId());
        } catch (Exception e) {
            log.warn("Failed to delete messages for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private List<Message> toMessages(List<Map<String, Object>> raw) {
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            String role = (String) m.get("role");
            String content = (String) m.get("content");
            if (role == null || content == null) {
                continue;
            }
            Message msg = switch (role) {
                case "assistant" -> new AssistantMessage(content);
                case "system" -> new SystemMessage(content);
                default -> new UserMessage(content);
            };
            messages.add(msg);
        }
        return messages;
    }
}
