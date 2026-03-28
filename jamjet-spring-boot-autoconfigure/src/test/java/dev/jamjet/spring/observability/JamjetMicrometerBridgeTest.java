package dev.jamjet.spring.observability;

import dev.jamjet.spring.JamjetProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetMicrometerBridgeTest {

    private SimpleMeterRegistry registry;
    private JamjetMicrometerBridge bridge;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        var props = new JamjetProperties();
        bridge = new JamjetMicrometerBridge(registry, props);
    }

    @Test
    void recordsExecutionDuration() {
        bridge.recordExecutionCompleted("Completed", Duration.ofSeconds(5));

        var timer = registry.find("jamjet.execution.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isCloseTo(5.0,
                org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void countsExecutions() {
        bridge.recordExecutionCompleted("Completed", Duration.ofSeconds(1));
        bridge.recordExecutionCompleted("Failed", Duration.ofSeconds(2));
        bridge.recordExecutionCompleted("Completed", Duration.ofSeconds(1));

        var completedCounter = registry.find("jamjet.execution.count")
                .tag("status", "Completed").counter();
        var failedCounter = registry.find("jamjet.execution.count")
                .tag("status", "Failed").counter();

        assertThat(completedCounter).isNotNull();
        assertThat(completedCounter.count()).isEqualTo(2.0);
        assertThat(failedCounter).isNotNull();
        assertThat(failedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordsToolCalls() {
        bridge.recordToolCall("WebSearch", Duration.ofMillis(200));
        bridge.recordToolCall("WebSearch", Duration.ofMillis(300));
        bridge.recordToolCall("Calculator", Duration.ofMillis(50));

        var webSearchCounter = registry.find("jamjet.tool.calls")
                .tag("tool_name", "WebSearch").counter();
        assertThat(webSearchCounter).isNotNull();
        assertThat(webSearchCounter.count()).isEqualTo(2.0);

        var webSearchTimer = registry.find("jamjet.tool.duration")
                .tag("tool_name", "WebSearch").timer();
        assertThat(webSearchTimer).isNotNull();
        assertThat(webSearchTimer.count()).isEqualTo(2);
    }

    @Test
    void recordsNodeCompletion() {
        bridge.recordNodeCompleted("chat", "LlmGenerate", Duration.ofSeconds(3), 0);
        bridge.recordNodeCompleted("search", "ToolCall", Duration.ofMillis(500), 2);

        var nodeTimer = registry.find("jamjet.node.duration")
                .tag("node_id", "chat").timer();
        assertThat(nodeTimer).isNotNull();
        assertThat(nodeTimer.count()).isEqualTo(1);

        var retryCounter = registry.find("jamjet.node.retries")
                .tag("node_id", "search").counter();
        assertThat(retryCounter).isNotNull();
        assertThat(retryCounter.count()).isEqualTo(2.0);
    }

    @Test
    void recordsExecutionCost() {
        bridge.recordExecutionCost(0.05);
        bridge.recordExecutionCost(0.12);

        var summary = registry.find("jamjet.execution.cost.usd").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isCloseTo(0.17, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void respectsCustomMetricPrefix() {
        var props = new JamjetProperties();
        props.getObservability().setMetricPrefix("myapp");
        var customBridge = new JamjetMicrometerBridge(registry, props);

        customBridge.recordExecutionCompleted("Completed", Duration.ofSeconds(1));

        assertThat(registry.find("myapp.execution.duration").timer()).isNotNull();
        assertThat(registry.find("jamjet.execution.duration").timer()).isNull();
    }
}
