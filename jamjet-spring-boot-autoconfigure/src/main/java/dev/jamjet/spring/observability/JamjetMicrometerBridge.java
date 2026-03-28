package dev.jamjet.spring.observability;

import dev.jamjet.spring.JamjetProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class JamjetMicrometerBridge {

    private static final Logger log = LoggerFactory.getLogger(JamjetMicrometerBridge.class);

    private final MeterRegistry registry;
    private final String prefix;

    public JamjetMicrometerBridge(MeterRegistry registry, JamjetProperties properties) {
        this.registry = registry;
        this.prefix = properties.getObservability().getMetricPrefix();
        log.debug("JamJet Micrometer bridge initialized with prefix '{}'", prefix);
    }

    public void recordExecutionCompleted(String status, Duration duration) {
        Timer.builder(prefix + ".execution.duration")
                .tag("status", status)
                .register(registry)
                .record(duration);
        Counter.builder(prefix + ".execution.count")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordNodeCompleted(String nodeId, String nodeKind, Duration duration, int retries) {
        Timer.builder(prefix + ".node.duration")
                .tag("node_id", nodeId)
                .tag("node_kind", nodeKind)
                .register(registry)
                .record(duration);
        if (retries > 0) {
            Counter.builder(prefix + ".node.retries")
                    .tag("node_id", nodeId)
                    .register(registry)
                    .increment(retries);
        }
    }

    public void recordToolCall(String toolName, Duration duration) {
        Counter.builder(prefix + ".tool.calls")
                .tag("tool_name", toolName)
                .register(registry)
                .increment();
        Timer.builder(prefix + ".tool.duration")
                .tag("tool_name", toolName)
                .register(registry)
                .record(duration);
    }

    public void recordExecutionCost(double costUsd) {
        DistributionSummary.builder(prefix + ".execution.cost.usd")
                .register(registry)
                .record(costUsd);
    }

    public void recordAuditEvent(String eventType) {
        Counter.builder(prefix + ".audit.events")
                .tag("event_type", eventType)
                .register(registry)
                .increment();
    }
}
