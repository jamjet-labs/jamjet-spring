package dev.jamjet.spring.observability;

import dev.jamjet.spring.JamjetProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JamjetOtelBridge {

    private static final Logger log = LoggerFactory.getLogger(JamjetOtelBridge.class);

    private final Tracer tracer;
    private final String prefix;

    public JamjetOtelBridge(Tracer tracer, JamjetProperties properties) {
        this.tracer = tracer;
        this.prefix = properties.getObservability().getMetricPrefix();
        log.debug("JamJet OpenTelemetry bridge initialized with prefix '{}'", prefix);
    }

    public Span startExecutionSpan(String executionId, String workflowId) {
        return tracer.spanBuilder(prefix + ".execution")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("jamjet.execution.id", executionId)
                .setAttribute("jamjet.workflow.id", workflowId)
                .startSpan();
    }

    public Span startNodeSpan(Span parent, String nodeId, String nodeKind) {
        return tracer.spanBuilder(prefix + ".node." + nodeId)
                .setParent(io.opentelemetry.context.Context.root().with(parent))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("jamjet.node.id", nodeId)
                .setAttribute("jamjet.node.kind", nodeKind)
                .startSpan();
    }

    public Span startToolSpan(Span parent, String toolName) {
        return tracer.spanBuilder(prefix + ".tool." + toolName)
                .setParent(io.opentelemetry.context.Context.root().with(parent))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("jamjet.tool.name", toolName)
                .startSpan();
    }

    public Span startModelSpan(Span parent, String modelName) {
        return tracer.spanBuilder(prefix + ".model.call")
                .setParent(io.opentelemetry.context.Context.root().with(parent))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("jamjet.model.name", modelName)
                .startSpan();
    }

    public void endSpan(Span span, double costUsd) {
        if (costUsd > 0) {
            span.setAttribute("jamjet.cost.usd", costUsd);
        }
        span.setStatus(StatusCode.OK);
        span.end();
    }

    public void endSpanWithError(Span span, Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(error);
        span.end();
    }
}
