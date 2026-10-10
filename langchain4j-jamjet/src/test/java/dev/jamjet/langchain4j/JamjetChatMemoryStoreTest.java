package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.RuntimeErrorMode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JamjetChatMemoryStoreTest {

    private StubRuntimeClient runtime;
    private JamjetChatMemoryStore store;

    @BeforeEach
    void setUp() {
        runtime = new StubRuntimeClient();
        store = new JamjetChatMemoryStore(runtime);
    }

    @Test
    void messagesPersistAndReadBack() {
        var messages = List.of(UserMessage.from("Hello"), AiMessage.from("Hi there!"));

        store.updateMessages("session-1", messages);

        assertThat(store.getMessages("session-1")).isEqualTo(messages);
    }

    @Test
    void eachMemoryIdIsItsOwnExecutionAndTheLatestUpdateWins() {
        store.updateMessages("a", List.of(UserMessage.from("one")));
        store.updateMessages("b", List.of(UserMessage.from("other")));
        store.updateMessages("a", List.of(UserMessage.from("one"), AiMessage.from("two")));

        assertThat(runtime.getExecutionCount()).isEqualTo(2);
        assertThat(store.getMessages("a")).containsExactly(UserMessage.from("one"), AiMessage.from("two"));
        assertThat(store.getMessages("b")).containsExactly(UserMessage.from("other"));
    }

    @Test
    void aNewStoreFindsMemoryWrittenBeforeARestart() {
        store.updateMessages("session-1", List.of(UserMessage.from("remember me")));

        var afterRestart = new JamjetChatMemoryStore(runtime);

        assertThat(afterRestart.getMessages("session-1")).containsExactly(UserMessage.from("remember me"));
    }

    @Test
    void anUnknownMemoryIsEmptyAndCreatesNothing() {
        assertThat(store.getMessages("never-written")).isEmpty();
        assertThat(runtime.getExecutionCount()).isZero();
    }

    @Test
    void deleteClearsTheMemory() {
        store.updateMessages("session-1", List.of(UserMessage.from("Hello")));

        store.deleteMessages("session-1");

        assertThat(store.getMessages("session-1")).isEmpty();
        assertThat(runtime.getEvents()).extracting(StubRuntimeClient.RecordedEvent::correlationKey)
                .containsExactly("chat_memory", "chat_memory_cleared");
    }

    @Test
    void failsClosedByDefaultWhenTheRuntimeIsDown() {
        runtime.setUnavailable(true);

        assertThatThrownBy(() -> store.updateMessages("session-1", List.of(UserMessage.from("x"))))
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> store.getMessages("session-1"))
                .isInstanceOf(JamjetGovernanceException.class);
    }

    @Test
    void failOpenReadsEmptyAndDropsWrites() {
        runtime.setUnavailable(true);
        var lenient = new JamjetChatMemoryStore(runtime, RuntimeErrorMode.FAIL_OPEN);

        lenient.updateMessages("session-1", List.of(UserMessage.from("x")));
        assertThat(lenient.getMessages("session-1")).isEmpty();
    }
}
