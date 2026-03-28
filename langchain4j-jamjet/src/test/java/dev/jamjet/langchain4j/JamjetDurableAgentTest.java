package dev.jamjet.langchain4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JamjetDurableAgentTest {

    private StubRuntimeClient stub;

    interface TestAssistant {
        String answer(String question);
    }

    static class SimpleAssistant implements TestAssistant {
        @Override
        public String answer(String question) {
            return "Answer to: " + question;
        }
    }

    @BeforeEach
    void setUp() {
        stub = new StubRuntimeClient();
    }

    @Test
    void wrapsInterfaceAndDelegates() {
        TestAssistant raw = new SimpleAssistant();
        TestAssistant durable = JamjetDurableAgent.wrap(raw, TestAssistant.class, stub);

        String result = durable.answer("What is Java?");

        assertThat(result).isEqualTo("Answer to: What is Java?");
    }

    @Test
    void recordsExecutionOnSuccess() {
        TestAssistant raw = new SimpleAssistant();
        TestAssistant durable = JamjetDurableAgent.wrap(raw, TestAssistant.class, stub);

        durable.answer("test question");

        assertThat(stub.getWorkflowCount()).isEqualTo(1);
        assertThat(stub.getExecutionCount()).isEqualTo(1);
        var events = stub.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().correlationKey()).isEqualTo("completion");
        assertThat(events.getFirst().payload().get("status")).isEqualTo("completed");
    }

    @Test
    void degradesGracefullyWhenRuntimeUnavailable() {
        stub.setUnavailable(true);
        TestAssistant raw = new SimpleAssistant();
        TestAssistant durable = JamjetDurableAgent.wrap(raw, TestAssistant.class, stub);

        String result = durable.answer("test");

        assertThat(result).isEqualTo("Answer to: test");
        assertThat(stub.getWorkflowCount()).isEqualTo(0);
    }

    @Test
    void recordsFailureOnException() {
        TestAssistant failing = question -> { throw new RuntimeException("LLM timeout"); };
        TestAssistant durable = JamjetDurableAgent.wrap(failing, TestAssistant.class, stub);

        assertThatThrownBy(() -> durable.answer("test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LLM timeout");

        var events = stub.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().payload().get("status")).isEqualTo("failed");
    }

    @Test
    void objectMethodsPassThrough() {
        TestAssistant raw = new SimpleAssistant();
        TestAssistant durable = JamjetDurableAgent.wrap(raw, TestAssistant.class, stub);

        durable.toString();
        durable.hashCode();

        assertThat(stub.getWorkflowCount()).isEqualTo(0);
    }
}
