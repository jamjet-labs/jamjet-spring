package dev.jamjet.spring.test;

import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
                state.initialInput(),
                state.currentState(),
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
        var nodeOutputs = new java.util.LinkedHashMap<String, Object>();
        var nodeStatuses = new java.util.LinkedHashMap<String, String>();
        var nodeRetries = new java.util.LinkedHashMap<String, Integer>();
        var nodeEnds = new java.util.LinkedHashMap<String, Instant>();

        for (ExecutionEvent event : events) {
            String nId = event.nodeId();
            if (nId == null || nId.isBlank()) continue;

            String type = event.type();
            if (type == null) continue;

            switch (type) {
                case "node_scheduled" -> {
                    Object queue = event.field("queue_type");
                    if (queue != null) nodeKinds.putIfAbsent(nId, queue.toString());
                }
                case "node_started" -> {
                    nodeStarts.putIfAbsent(nId, event.createdAt());
                    nodeStatuses.put(nId, "running");
                    nodeRetries.putIfAbsent(nId, 0);
                }
                case "node_completed" -> {
                    nodeStarts.putIfAbsent(nId, event.createdAt());
                    nodeEnds.put(nId, event.createdAt());
                    nodeOutputs.put(nId, event.field("output"));
                    nodeStatuses.put(nId, "completed");
                }
                case "node_failed" -> {
                    nodeStarts.putIfAbsent(nId, event.createdAt());
                    nodeEnds.put(nId, event.createdAt());
                    nodeOutputs.put(nId, event.field("error"));
                    nodeStatuses.put(nId, "failed");
                }
                case "retry_scheduled" -> nodeRetries.merge(nId, 1, Integer::sum);
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
                    null,
                    nodeOutputs.get(nId),
                    Duration.between(start, end),
                    nodeRetries.getOrDefault(nId, 0)
            ));
        }
        return nodes;
    }

    private static int countToolCalls(List<ExecutionEvent> events) {
        return (int) events.stream().filter(RecordedExecution::isToolCall).count();
    }

    @SuppressWarnings("unchecked")
    private static double extractCost(List<ExecutionEvent> events) {
        return events.stream()
                .filter(e -> e.field("cost_usd") instanceof Number)
                .mapToDouble(e -> ((Number) e.field("cost_usd")).doubleValue())
                .sum();
    }

    private static Duration computeDuration(ExecutionState state, List<ExecutionEvent> events) {
        if (state.startedAt() != null && state.completedAt() != null) {
            return Duration.between(state.startedAt(), state.completedAt());
        }
        return computeDurationFromEvents(events);
    }

    private static Duration computeDurationFromEvents(List<ExecutionEvent> events) {
        if (events.isEmpty()) return Duration.ZERO;
        Instant first = events.getFirst().createdAt();
        Instant last = events.getLast().createdAt();
        return (first != null && last != null) ? Duration.between(first, last) : Duration.ZERO;
    }

    private static boolean isCompletionEvent(ExecutionEvent event) {
        String type = event.type();
        return "node_completed".equals(type) || "node_failed".equals(type);
    }

    /** A tool invocation: {@code tool_called} (strategy loops) or {@code agent_tool_invoked}. */
    static boolean isToolCall(ExecutionEvent event) {
        String type = event.type();
        return "tool_called".equals(type) || "agent_tool_invoked".equals(type);
    }
}
