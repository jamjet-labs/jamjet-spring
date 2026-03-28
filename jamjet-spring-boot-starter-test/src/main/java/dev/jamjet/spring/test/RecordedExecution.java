package dev.jamjet.spring.test;

import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record RecordedExecution(
        String executionId,
        String workflowId,
        String status,
        Object input,
        Object finalState,
        List<ExecutionEvent> events,
        List<NodeExecution> nodes,
        Duration totalDuration,
        int toolCallCount,
        double totalCostUsd
) {

    public record NodeExecution(
            String nodeId,
            String kind,
            String status,
            Object input,
            Object output,
            Duration duration,
            int retryCount
    ) {}

    public static RecordedExecution fromStateAndEvents(ExecutionState state, List<ExecutionEvent> events) {
        List<NodeExecution> nodes = parseNodes(events);
        int toolCalls = countToolCalls(events);
        double cost = extractCost(events);
        Duration duration = computeDuration(state, events);

        return new RecordedExecution(
                state.executionId(),
                state.workflowId(),
                state.status(),
                state.state() != null ? state.state().get("input") : null,
                state.state(),
                List.copyOf(events),
                List.copyOf(nodes),
                duration,
                toolCalls,
                cost
        );
    }

    public RecordedExecution forkAt(String nodeId) {
        List<ExecutionEvent> truncated = new ArrayList<>();
        for (ExecutionEvent event : events) {
            truncated.add(event);
            if (nodeId.equals(event.nodeId()) && isCompletionEvent(event)) {
                break;
            }
        }
        List<NodeExecution> truncatedNodes = parseNodes(truncated);
        return new RecordedExecution(
                executionId, workflowId, "forked", input, finalState,
                List.copyOf(truncated),
                List.copyOf(truncatedNodes),
                computeDurationFromEvents(truncated),
                countToolCalls(truncated),
                extractCost(truncated)
        );
    }

    private static List<NodeExecution> parseNodes(List<ExecutionEvent> events) {
        var nodeStarts = new java.util.LinkedHashMap<String, Instant>();
        var nodeKinds = new java.util.LinkedHashMap<String, String>();
        var nodeInputs = new java.util.LinkedHashMap<String, Object>();
        var nodeOutputs = new java.util.LinkedHashMap<String, Object>();
        var nodeStatuses = new java.util.LinkedHashMap<String, String>();
        var nodeRetries = new java.util.LinkedHashMap<String, Integer>();
        var nodeEnds = new java.util.LinkedHashMap<String, Instant>();

        for (ExecutionEvent event : events) {
            String nId = event.nodeId();
            if (nId == null || nId.isBlank()) continue;

            Map<String, Object> payload = event.payload() != null ? event.payload() : Map.of();
            String type = event.eventType();

            switch (type) {
                case "NodeStarted" -> {
                    nodeStarts.putIfAbsent(nId, event.timestamp());
                    nodeKinds.putIfAbsent(nId, (String) payload.get("kind"));
                    nodeInputs.putIfAbsent(nId, payload.get("input"));
                    nodeStatuses.put(nId, "running");
                    nodeRetries.putIfAbsent(nId, 0);
                }
                case "NodeCompleted" -> {
                    nodeEnds.put(nId, event.timestamp());
                    nodeOutputs.put(nId, payload.get("output"));
                    nodeStatuses.put(nId, "completed");
                }
                case "NodeFailed" -> {
                    nodeEnds.put(nId, event.timestamp());
                    nodeOutputs.put(nId, payload.get("error"));
                    nodeStatuses.put(nId, "failed");
                }
                case "NodeRetried" -> {
                    nodeRetries.merge(nId, 1, Integer::sum);
                }
                default -> { }
            }
        }

        List<NodeExecution> nodes = new ArrayList<>();
        for (String nId : nodeStarts.keySet()) {
            Instant start = nodeStarts.get(nId);
            Instant end = nodeEnds.getOrDefault(nId, start);
            nodes.add(new NodeExecution(
                    nId,
                    nodeKinds.getOrDefault(nId, "unknown"),
                    nodeStatuses.getOrDefault(nId, "unknown"),
                    nodeInputs.get(nId),
                    nodeOutputs.get(nId),
                    Duration.between(start, end),
                    nodeRetries.getOrDefault(nId, 0)
            ));
        }
        return nodes;
    }

    private static int countToolCalls(List<ExecutionEvent> events) {
        return (int) events.stream()
                .filter(e -> "ToolCallStarted".equals(e.eventType()) || "ToolCall".equals(e.eventType()))
                .count();
    }

    @SuppressWarnings("unchecked")
    private static double extractCost(List<ExecutionEvent> events) {
        return events.stream()
                .filter(e -> e.payload() != null && e.payload().containsKey("cost_usd"))
                .mapToDouble(e -> ((Number) e.payload().get("cost_usd")).doubleValue())
                .sum();
    }

    private static Duration computeDuration(ExecutionState state, List<ExecutionEvent> events) {
        if (state.createdAt() != null && state.completedAt() != null) {
            return Duration.between(state.createdAt(), state.completedAt());
        }
        return computeDurationFromEvents(events);
    }

    private static Duration computeDurationFromEvents(List<ExecutionEvent> events) {
        if (events.isEmpty()) return Duration.ZERO;
        Instant first = events.getFirst().timestamp();
        Instant last = events.getLast().timestamp();
        return (first != null && last != null) ? Duration.between(first, last) : Duration.ZERO;
    }

    private static boolean isCompletionEvent(ExecutionEvent event) {
        String type = event.eventType();
        return "NodeCompleted".equals(type) || "NodeFailed".equals(type);
    }
}
