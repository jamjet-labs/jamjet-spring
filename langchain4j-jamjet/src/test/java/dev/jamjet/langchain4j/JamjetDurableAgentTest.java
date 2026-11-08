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
        assertThat(ir).containsEntry("version", "2.0.0");
        assertThat(((Map<?, ?>) ir.get("nodes")).containsKey(ir.get("start_node"))).isTrue();
        assertThat(ir.get("edges")).isInstanceOf(List.class);
        var node = (Map<?, ?>) ((Map<?, ?>) ir.get("nodes")).get(ir.get("start_node"));
        @SuppressWarnings("unchecked")
        var kind = (Map<String, Object>) node.get("kind");
        assertThat(kind).containsEntry("type", "external_fn")
                .containsEntry("queue", "langchain4j-durable-agent.calls");

        // Each call's node was claimed and completed with its outcome.
        assertThat(runtime.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.settled).isEqualTo("completed");
            assertThat(item.output).containsEntry("status", "completed");
            assertThat(runtime.status(item.executionId)).isEqualTo("completed");
        });

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
        assertThat(runtime.items()).singleElement().satisfies(item -> {
            assertThat(item.settled).as("a failed call fails its node").isEqualTo("failed");
            assertThat(item.output).containsEntry("error", "LLM timeout");
        });
    }

    @Test
    void whenTheOutcomeEventCannotBeWrittenTheNodeStillGetsTheOutcome() {
        runtime.setEventsFail(true);
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime);

        assertThatThrownBy(() -> durable.answer("test"))
                .isInstanceOf(dev.jamjet.spring.JamjetRecordingException.class);
        assertThat(runtime.items()).singleElement().satisfies(item -> {
            assertThat(item.settled).isEqualTo("completed");
            assertThat(item.output).containsEntry("status", "completed");
        });
    }

    @Test
    void objectMethodsPassThrough() {
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime);

        durable.toString();
        durable.hashCode();

        assertThat(runtime.getWorkflowCalls()).isZero();
    }

    @Test
    void aRecordFailureAfterTheCallRanSaysTheCallCompleted() {
        runtime.setEventsFail(true);
        var raw = new SimpleAssistant();
        TestAssistant durable = JamjetDurableAgent.wrap(raw, TestAssistant.class, runtime);

        assertThatThrownBy(() -> durable.answer("test"))
                .isInstanceOf(dev.jamjet.spring.JamjetRecordingException.class)
                .hasMessageContaining("call completed")
                .hasMessageContaining("event store unavailable");
        assertThat(raw.calls).isEqualTo(1);
    }

    @Test
    void whenTheCallFailedAndTheRecordFailsTheCallersErrorIsKept() {
        runtime.setEventsFail(true);
        TestAssistant failing = question -> { throw new IllegalStateException("LLM timeout"); };
        TestAssistant durable = JamjetDurableAgent.wrap(failing, TestAssistant.class, runtime);

        assertThatThrownBy(() -> durable.answer("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LLM timeout")
                .satisfies(e -> assertThat(e.getSuppressed())
                        .anySatisfy(x -> assertThat(x).isInstanceOf(dev.jamjet.spring.JamjetRecordingException.class)));
    }

    @Test
    void contentRecordingCanBeTurnedOff() {
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime,
                RuntimeErrorMode.FAIL_CLOSED, false);

        durable.answer("my account number is 12345");

        var input = runtime.executions().get(0).initialInput();
        assertThat(input).doesNotContainKey("args").containsEntry("arg_count", 1);
        assertThat(runtime.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.payload()).containsEntry("status", "completed").doesNotContainKey("result");
            assertThat(e.payload().toString()).doesNotContain("12345");
        });
    }

    @Test
    void withContentOffAFailureRecordsOnlyTheErrorType() {
        TestAssistant failing = q -> { throw new IllegalStateException("leaked " + q); };
        TestAssistant durable = JamjetDurableAgent.wrap(failing, TestAssistant.class, runtime,
                RuntimeErrorMode.FAIL_CLOSED, false);

        assertThatThrownBy(() -> durable.answer("secret-42")).isInstanceOf(IllegalStateException.class);

        assertThat(runtime.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.payload()).containsEntry("status", "failed").containsEntry("error", "IllegalStateException");
            assertThat(e.payload().toString()).doesNotContain("secret-42");
        });
    }

    @Test
    void contentIsRecordedByDefault() {
        JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime).answer("visible");

        assertThat(runtime.executions().get(0).initialInput()).containsKey("args");
        assertThat(runtime.getEvents().get(0).payload()).containsEntry("result", "Answer to: visible");
        assertThat(new JamjetConfig().isRecordContent()).isTrue();
    }

    @Test
    void aRuntimeThatLostTheWorkflowGetsItRegisteredAgain() {
        TestAssistant durable = JamjetDurableAgent.wrap(new SimpleAssistant(), TestAssistant.class, runtime);
        durable.answer("first");
        runtime.forgetWorkflows();

        assertThat(durable.answer("second")).isEqualTo("Answer to: second");
        assertThat(runtime.getWorkflowCalls()).isEqualTo(2);
        assertThat(runtime.getExecutionCount()).isEqualTo(2);
    }
}
