package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.RuntimeErrorMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JamjetDurableAgentTest {

    private StubRuntimeClient runtime;

    interface TestAssistant {
        String answer(String question);
    }

    static class SimpleAssistant implements TestAssistant {
        int calls;

        @Override
        public String answer(String question) {
            calls++;
            return "Answer to: " + question;
        }
    }

    @BeforeEach
    void setUp() {
        runtime = new StubRuntimeClient();
    }

    @Test
    void wrapsInterfaceAndDelegates() {
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime);

        assertThat(durable.answer("What is Java?")).isEqualTo("Answer to: What is Java?");
    }

    @Test
    void registersOneValidWorkflowAndRecordsEachCall() {
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime);

        durable.answer("first");
        durable.answer("second");

        assertThat(runtime.getWorkflowCalls()).isEqualTo(1);
        var ir = runtime.registeredIr(JamjetDurableAgent.WORKFLOW_ID);
        assertThat(ir).containsEntry("version", "1.0.0");
        assertThat(((Map<?, ?>) ir.get("nodes")).containsKey(ir.get("start_node"))).isTrue();
        assertThat(ir.get("edges")).isInstanceOf(List.class);

        assertThat(runtime.getExecutionCount()).isEqualTo(2);
        var events = runtime.getEvents();
        assertThat(events).hasSize(2).allSatisfy(e -> {
            assertThat(e.correlationKey()).isEqualTo("langchain4j.completed");
            assertThat(e.payload()).containsEntry("status", "completed");
        });
    }

    @Test
    void failsClosedByDefaultWhenTheRuntimeIsDown() {
        runtime.setUnavailable(true);
        var raw = new SimpleAssistant();
        TestAssistant durable = JamjetDurableAgent.wrap(raw, TestAssistant.class, runtime);

        assertThatThrownBy(() -> durable.answer("test"))
                .isInstanceOf(JamjetGovernanceException.class)
                .hasMessageContaining("unavailable");
        assertThat(raw.calls).as("the call never runs unrecorded").isZero();
    }

    @Test
    void failOpenRunsTheCallWhenTheRuntimeIsDown() {
        runtime.setUnavailable(true);
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime,
                RuntimeErrorMode.FAIL_OPEN);

        assertThat(durable.answer("test")).isEqualTo("Answer to: test");
    }

    @Test
    void recordsFailureOnException() {
        TestAssistant failing = question -> { throw new RuntimeException("LLM timeout"); };
        TestAssistant durable = JamjetDurableAgent.wrap(failing, TestAssistant.class, runtime);

        assertThatThrownBy(() -> durable.answer("test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LLM timeout");

        assertThat(runtime.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.correlationKey()).isEqualTo("langchain4j.completed");
            assertThat(e.payload()).containsEntry("status", "failed");
        });
    }

    @Test
    void objectMethodsPassThrough() {
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime);

        durable.toString();
        durable.hashCode();

        assertThat(runtime.getWorkflowCalls()).isZero();
    }
}
