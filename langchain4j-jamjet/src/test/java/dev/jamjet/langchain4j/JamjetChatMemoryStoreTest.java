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
        store = new JamjetChatMemoryStore(runtime, "app-a");
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

        var afterRestart = new JamjetChatMemoryStore(runtime, "app-a");

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
        var lenient = new JamjetChatMemoryStore(runtime, "app-a", RuntimeErrorMode.FAIL_OPEN);

        lenient.updateMessages("session-1", List.of(UserMessage.from("x")));
        assertThat(lenient.getMessages("session-1")).isEmpty();
    }

    @Test
    void twoApplicationsOnOneTenantDoNotShareMemory() {
        var other = new JamjetChatMemoryStore(runtime, "app-b");

        store.updateMessages("default", List.of(UserMessage.from("a's secret")));
        other.updateMessages("default", List.of(UserMessage.from("b's note")));

        assertThat(new JamjetChatMemoryStore(runtime, "app-a").getMessages("default"))
                .containsExactly(UserMessage.from("a's secret"));
        assertThat(new JamjetChatMemoryStore(runtime, "app-b").getMessages("default"))
                .containsExactly(UserMessage.from("b's note"));
    }

    @Test
    void aNamespaceIsRequired() {
        assertThatThrownBy(() -> new JamjetChatMemoryStore(runtime, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewConversationScansTheRuntimeOnce() {
        store.getMessages("new-conversation");
        store.updateMessages("new-conversation", List.of(UserMessage.from("hi")));
        store.getMessages("new-conversation");

        assertThat(runtime.listCalls).hasValue(1);
    }

    @Test
    void differentConversationsDoNotWaitForEachOthersScan() throws Exception {
        runtime.listGate = new java.util.concurrent.CountDownLatch(1);
        var first = java.util.concurrent.CompletableFuture.runAsync(
                () -> store.updateMessages("one", List.of(UserMessage.from("1"))));
        var second = java.util.concurrent.CompletableFuture.runAsync(
                () -> store.updateMessages("two", List.of(UserMessage.from("2"))));

        long deadline = System.nanoTime() + 3_000_000_000L;
        while (runtime.listingNow.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        int concurrent = runtime.listingNow.get();
        runtime.listGate.countDown();
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        second.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(concurrent).as("two new ids scan at the same time").isEqualTo(2);
    }

    @Test
    void aRuntimeThatLostTheWorkflowGetsItRegisteredAgain() {
        store.updateMessages("a", List.of(UserMessage.from("one")));
        runtime.forgetWorkflows();

        store.updateMessages("b", List.of(UserMessage.from("two")));

        assertThat(store.getMessages("b")).containsExactly(UserMessage.from("two"));
    }

    @Test
    void theIdCachesStayBoundedAndAnEvictedIdIsFoundAgain() {
        var small = new JamjetChatMemoryStore(runtime, "app-a", RuntimeErrorMode.FAIL_CLOSED,
                java.time.Duration.ofSeconds(30), 3);
        for (int i = 0; i < 10; i++) {
            small.updateMessages("conv-" + i, List.of(UserMessage.from("m" + i)));
            small.getMessages("never-" + i);
        }

        assertThat(small.cachedIds()).isLessThanOrEqualTo(6);
        assertThat(small.getMessages("conv-0")).containsExactly(UserMessage.from("m0"));
    }
}
