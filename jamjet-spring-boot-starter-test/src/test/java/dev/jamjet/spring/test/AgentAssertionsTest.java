package dev.jamjet.spring.test;

import dev.jamjet.spring.client.model.ExecutionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

class AgentAssertionsTest {

    private RecordedExecution completedExecution;
    private RecordedExecution failedExecution;

    @BeforeEach
    void setUp() {
        var now = Instant.now();
        var completedState = new ExecutionState(
                "exec-ok", "wf-1", "1.0.0", "completed", Map.of("output", "quarterly revenue was $1.2B"), Map.of("output", "quarterly revenue was $1.2B"),
                now.minus(Duration.ofSeconds(5)), now, now
        );
        var completedEvents = List.of(
                TestEvents.event("e1", "exec-ok", "node_scheduled", "chat",
                        Map.of("queue_type", "model"), now.minus(Duration.ofSeconds(5))),
                TestEvents.event("e2", "exec-ok", "tool_called", "chat",
                        Map.of("tool", "WebSearch"), now.minus(Duration.ofSeconds(4))),
                TestEvents.event("e3", "exec-ok", "tool_called", "chat",
                        Map.of("tool", "WebSearch"), now.minus(Duration.ofSeconds(3))),
                TestEvents.event("e4", "exec-ok", "node_completed", "chat",
                        Map.of("output", "quarterly revenue was $1.2B", "cost_usd", 0.03), now)
        );
        completedExecution = RecordedExecution.fromStateAndEvents(completedState, completedEvents);

        var failedState = new ExecutionState(
                "exec-fail", "wf-1", "1.0.0", "failed", Map.of(), Map.of(),
                now.minus(Duration.ofSeconds(2)), now, null
        );
        var failedEvents = List.of(
                TestEvents.event("e1", "exec-fail", "node_scheduled", "chat",
                        Map.of("queue_type", "model"), now.minus(Duration.ofSeconds(2))),
                TestEvents.event("e2", "exec-fail", "node_failed", "chat",
                        Map.of("error", "timeout"), now)
        );
        failedExecution = RecordedExecution.fromStateAndEvents(failedState, failedEvents);
    }

    @Test
    void completedSuccessfullyPasses() {
        AgentAssertions.assertThat(completedExecution).completedSuccessfully();
    }

    @Test
    void completedSuccessfullyFailsOnFailedExecution() {
        assertThatThrownBy(() -> AgentAssertions.assertThat(failedExecution).completedSuccessfully())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("failed");
    }

    @Test
    void usedToolPasses() {
        AgentAssertions.assertThat(completedExecution).usedTool("WebSearch");
    }

    @Test
    void catchesMissingToolCall() {
        assertThatThrownBy(() -> AgentAssertions.assertThat(completedExecution).usedTool("DeleteFile"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DeleteFile");
    }

    @Test
    void usedToolTimesPasses() {
        AgentAssertions.assertThat(completedExecution).usedToolTimes("WebSearch", 2);
    }

    @Test
    void didNotUseToolPasses() {
        AgentAssertions.assertThat(completedExecution).didNotUseTool("DeleteFile");
    }

    @Test
    void completedWithinPasses() {
        AgentAssertions.assertThat(completedExecution).completedWithin(30, TimeUnit.SECONDS);
    }

    @Test
    void completedWithinFails() {
        assertThatThrownBy(() -> AgentAssertions.assertThat(completedExecution).completedWithin(1, TimeUnit.MILLISECONDS))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void costLessThanPasses() {
        AgentAssertions.assertThat(completedExecution).costLessThan(1.00);
    }

    @Test
    void outputContainsPasses() {
        AgentAssertions.assertThat(completedExecution).outputContains("quarterly revenue");
    }

    @Test
    void toolCallCountPasses() {
        AgentAssertions.assertThat(completedExecution).toolCallCount(lessThanOrEqualTo(5));
    }

    @Test
    void nodeCompletedPasses() {
        AgentAssertions.assertThat(completedExecution).nodeCompleted("chat");
    }

    @Test
    void chainingWorks() {
        AgentAssertions.assertThat(completedExecution)
                .completedSuccessfully()
                .usedTool("WebSearch")
                .didNotUseTool("DeleteFile")
                .completedWithin(30, TimeUnit.SECONDS)
                .costLessThan(1.00)
                .outputContains("revenue");
    }

    @Test
    void failedWithPasses() {
        AgentAssertions.assertThat(failedExecution).failedWith("timeout");
    }
}
