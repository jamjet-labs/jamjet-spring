package dev.jamjet.spring.test;

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
                "exec-1", "wf-1", "1.0.0", "completed", Map.of("input", "test prompt"), Map.of("input", "test prompt"),
                now.minus(Duration.ofSeconds(10)), now, now
        );
        var events = List.of(
                TestEvents.event("e1", "exec-1", "node_scheduled", "chat",
                        Map.of("queue_type", "model"), now.minus(Duration.ofSeconds(10))),
                TestEvents.event("e2", "exec-1", "tool_called", "chat",
                        Map.of("tool", "WebSearch"), now.minus(Duration.ofSeconds(8))),
                TestEvents.event("e3", "exec-1", "node_completed", "chat",
                        Map.of("output", "result text", "cost_usd", 0.05),
                        now)
        );

        var recorded = RecordedExecution.fromStateAndEvents(state, events);

        assertThat(recorded.executionId()).isEqualTo("exec-1");
        assertThat(recorded.workflowId()).isEqualTo("wf-1");
        assertThat(recorded.status()).isEqualTo("completed");
        assertThat(recorded.totalDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(recorded.toolCallCount()).isEqualTo(1);
        assertThat(recorded.totalCostUsd()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.001));
        assertThat(recorded.nodes()).hasSize(1);
        assertThat(recorded.nodes().getFirst().nodeId()).isEqualTo("chat");
        assertThat(recorded.nodes().getFirst().kind()).isEqualTo("model");
        assertThat(recorded.nodes().getFirst().status()).isEqualTo("completed");
    }

    @Test
    void forkAtNodeTruncatesEvents() {
        var now = Instant.now();
        var state = new ExecutionState(
                "exec-2", "wf-1", "1.0.0", "completed", Map.of(), Map.of(),
                now.minus(Duration.ofSeconds(20)), now, now
        );
        var events = List.of(
                TestEvents.event("e1", "exec-2", "node_scheduled", "search",
                        Map.of("queue_type", "tool"), now.minus(Duration.ofSeconds(20))),
                TestEvents.event("e2", "exec-2", "node_completed", "search",
                        Map.of("output", "search result"), now.minus(Duration.ofSeconds(15))),
                TestEvents.event("e3", "exec-2", "node_scheduled", "summarize",
                        Map.of("queue_type", "model"), now.minus(Duration.ofSeconds(14))),
                TestEvents.event("e4", "exec-2", "node_completed", "summarize",
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
