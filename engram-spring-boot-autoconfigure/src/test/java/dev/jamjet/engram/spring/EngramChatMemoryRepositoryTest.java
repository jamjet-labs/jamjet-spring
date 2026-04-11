package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EngramChatMemoryRepositoryTest {

    private EngramClient client;
    private EngramProperties properties;
    private EngramChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(EngramClient.class);
        properties = new EngramProperties();
        properties.setDefaultUserId("test-user");
        properties.setDefaultOrgId("test-org");
        repository = new EngramChatMemoryRepository(client, properties);
    }

    @Test
    void saveAll_sendsMessagesToClient() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi there!"));

        when(client.saveMessages(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(Map.of("saved", 2));

        repository.saveAll("conv-1", messages);

        verify(client).saveMessages(
                eq("conv-1"),
                argThat(list -> list.size() == 2
                        && "user".equals(list.get(0).get("role"))
                        && "Hello".equals(list.get(0).get("content"))
                        && "assistant".equals(list.get(1).get("role"))
                        && "Hi there!".equals(list.get(1).get("content"))),
                eq("test-user"),
                eq("test-org"));
    }

    @Test
    void saveAll_propagatesExceptionOnFailure() {
        when(client.saveMessages(anyString(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> repository.saveAll("conv-1", List.of(new UserMessage("Hi"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Engram message save failed");
    }

    @Test
    void findByConversationId_returnsMessages() {
        when(client.getMessages(eq("conv-1"), isNull(), eq("test-user"), eq("test-org")))
                .thenReturn(List.of(
                        Map.of("role", "user", "content", "Hello"),
                        Map.of("role", "assistant", "content", "Hi!"),
                        Map.of("role", "system", "content", "You are a helper.")));

        List<Message> result = repository.findByConversationId("conv-1");

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("Hello");
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1).getText()).isEqualTo("Hi!");
        assertThat(result.get(2)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(2).getText()).isEqualTo("You are a helper.");
    }

    @Test
    void findByConversationId_skipsEntriesWithMissingFields() {
        when(client.getMessages(anyString(), isNull(), anyString(), anyString()))
                .thenReturn(List.of(
                        Map.of("role", "user", "content", "Hello"),
                        Map.of("role", "assistant"),       // missing content
                        Map.of("content", "orphan")));     // missing role

        List<Message> result = repository.findByConversationId("conv-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("Hello");
    }

    @Test
    void findByConversationId_returnsEmptyOnError() {
        when(client.getMessages(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        List<Message> result = repository.findByConversationId("conv-1");

        assertThat(result).isEmpty();
    }

    @Test
    void findConversationIds_returnsIds() {
        when(client.listConversations(eq("test-user"), eq("test-org")))
                .thenReturn(List.of("conv-1", "conv-2", "conv-3"));

        List<String> result = repository.findConversationIds();

        assertThat(result).containsExactly("conv-1", "conv-2", "conv-3");
    }

    @Test
    void findConversationIds_returnsEmptyOnError() {
        when(client.listConversations(anyString(), anyString()))
                .thenThrow(new RuntimeException("timeout"));

        List<String> result = repository.findConversationIds();

        assertThat(result).isEmpty();
    }

    @Test
    void deleteByConversationId_callsClient() {
        when(client.deleteMessages(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("deleted", 5));

        repository.deleteByConversationId("conv-1");

        verify(client).deleteMessages("conv-1", "test-user", "test-org");
    }

    @Test
    void deleteByConversationId_swallowsException() {
        when(client.deleteMessages(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("server error"));

        // Should not throw
        repository.deleteByConversationId("conv-1");

        verify(client).deleteMessages("conv-1", "test-user", "test-org");
    }
}
