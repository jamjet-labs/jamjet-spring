package dev.jamjet.langchain4j;

import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class JamjetChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JamjetChatMemoryStore.class);

    private final JamjetRuntimeClient client;

    public JamjetChatMemoryStore(JamjetRuntimeClient client) {
        this.client = client;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        try {
            var auditPage = client.queryAudit(id, null, "chat_memory", 200, 0);
            if (auditPage.items().isEmpty()) {
                return List.of();
            }
            var lastEntry = auditPage.items().getLast();
            Object messagesJson = lastEntry.payload() != null
                    ? lastEntry.payload().get("messages_json")
                    : null;
            if (messagesJson == null) {
                return List.of();
            }
            return ChatMessageDeserializer.messagesFromJson(messagesJson.toString());
        } catch (JamjetClientException e) {
            log.warn("Failed to retrieve messages for memory {}: {}", id, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        try {
            String messagesJson = ChatMessageSerializer.messagesToJson(messages);
            client.sendExternalEvent(id, "chat_memory",
                    Map.of("messages_json", messagesJson, "count", messages.size()));
        } catch (JamjetClientException e) {
            log.warn("Failed to persist messages for memory {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        try {
            client.sendExternalEvent(id, "memory_cleared",
                    Map.of("memory_id", id));
        } catch (JamjetClientException e) {
            log.warn("Failed to record memory deletion for {}: {}", id, e.getMessage());
        }
    }
}
