package dev.jamjet.spring.test;

import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetReplayExtensionTest {

    @Test
    void parsesExecutionFromStateAndEvents() {
        var now = Instant.now();
        var state = new ExecutionState(
                "exec-1", "wf-1", "Completed", null,
                Map.of("input", "test prompt"),
                now.minus(Duration.ofSeconds(10)), now, now, null
        );
        var events = List.of(
                new ExecutionEvent("e1", "exec-1", "NodeStarted", "chat",
                        Map.of("kind", "LlmGenerate"), now.minus(Duration.ofSeconds(10))),
                new ExecutionEvent("e2", "exec-1", "ToolCallStarted", "chat",
                        Map.of("tool_name", "WebSearch"), now.minus(Duration.ofSeconds(8))),
                new ExecutionEvent("e3", "exec-1", "NodeCompleted", "chat",
                        Map.of("output", "result text", "cost_usd", 0.05),
                        now)
        );

        var recorded = RecordedExecution.fromStateAndEvents(state, events);

        assertThat(recorded.executionId()).isEqualTo("exec-1");
        assertThat(recorded.workflowId()).isEqualTo("wf-1");
        assertThat(recorded.status()).isEqualTo("Completed");
        assertThat(recorded.totalDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(recorded.toolCallCount()).isEqualTo(1);
        assertThat(recorded.totalCostUsd()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.001));
        assertThat(recorded.nodes()).hasSize(1);
        assertThat(recorded.nodes().getFirst().nodeId()).isEqualTo("chat");
        assertThat(recorded.nodes().getFirst().kind()).isEqualTo("LlmGenerate");
        assertThat(recorded.nodes().getFirst().status()).isEqualTo("completed");
    }

    @Test
    void forkAtNodeTruncatesEvents() {
        var now = Instant.now();
        var state = new ExecutionState(
                "exec-2", "wf-1", "Completed", null, Map.of(),
                now.minus(Duration.ofSeconds(20)), now, now, null
        );
        var events = List.of(
                new ExecutionEvent("e1", "exec-2", "NodeStarted", "search",
                        Map.of("kind", "ToolCall"), now.minus(Duration.ofSeconds(20))),
                new ExecutionEvent("e2", "exec-2", "NodeCompleted", "search",
                        Map.of("output", "search result"), now.minus(Duration.ofSeconds(15))),
                new ExecutionEvent("e3", "exec-2", "NodeStarted", "summarize",
                        Map.of("kind", "LlmGenerate"), now.minus(Duration.ofSeconds(14))),
                new ExecutionEvent("e4", "exec-2", "NodeCompleted", "summarize",
                        Map.of("output", "summary"), now)
        );

        var recorded = RecordedExecution.fromStateAndEvents(state, events);
        var forked = recorded.forkAt("search");

        assertThat(forked.status()).isEqualTo("forked");
        assertThat(forked.events()).hasSize(2);
        assertThat(forked.nodes()).hasSize(1);
        assertThat(forked.nodes().getFirst().nodeId()).isEqualTo("search");
    }
}
