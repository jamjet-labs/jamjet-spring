package dev.jamjet.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetChatMemoryStoreTest {

    private StubRuntimeClient stub;
    private JamjetChatMemoryStore store;

    @BeforeEach
    void setUp() {
        stub = new StubRuntimeClient();
        store = new JamjetChatMemoryStore(stub);
    }

    @Test
    void updateStoresMessagesAsExternalEvents() {
        var messages = List.of(
                UserMessage.from("Hello"),
                AiMessage.from("Hi there!")
        );

        store.updateMessages("session-1", messages);

        var events = stub.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().executionId()).isEqualTo("session-1");
        assertThat(events.getFirst().correlationKey()).isEqualTo("chat_memory");
    }

    @Test
    void deleteMessagesSendsEvent() {
        store.deleteMessages("session-1");

        var events = stub.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().correlationKey()).isEqualTo("memory_cleared");
    }
}
