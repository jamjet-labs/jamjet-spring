package dev.jamjet.spring.test;

import org.hamcrest.Matcher;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;


public final class AgentAssertions {

    private AgentAssertions() {}

    public static AgentAssert assertThat(RecordedExecution execution) {
        return new AgentAssert(execution);
    }

    public static class AgentAssert {
        private final RecordedExecution execution;

        AgentAssert(RecordedExecution execution) {
            if (execution == null) {
                throw new AssertionError("RecordedExecution must not be null");
            }
            this.execution = execution;
        }

        public AgentAssert completedSuccessfully() {
            if (!"Completed".equalsIgnoreCase(execution.status())) {
                throw new AssertionError(
                        "Expected execution to complete successfully but status was: " + execution.status());
            }
            return this;
        }

        public AgentAssert failedWith(String errorContaining) {
            if (!"Failed".equalsIgnoreCase(execution.status())) {
                throw new AssertionError(
                        "Expected execution to have failed but status was: " + execution.status());
            }
            boolean found = execution.events().stream()
                    .filter(e -> "node_failed".equals(e.type()))
                    .anyMatch(e -> {
                        Object error = e.field("error");
                        return error != null && error.toString().contains(errorContaining);
                    });
            if (!found) {
                throw new AssertionError(
                        "Expected failure containing '" + errorContaining + "' but no matching NodeFailed event found");
            }
            return this;
        }

        public AgentAssert wasCancelled() {
            if (!"Cancelled".equalsIgnoreCase(execution.status())) {
                throw new AssertionError(
                        "Expected execution to be cancelled but status was: " + execution.status());
            }
            return this;
        }

        public AgentAssert completedWithin(long amount, TimeUnit unit) {
            Duration limit = Duration.of(amount, unit.toChronoUnit());
            if (execution.totalDuration().compareTo(limit) > 0) {
                throw new AssertionError(
                        "Expected execution to complete within " + limit
                                + " but took " + execution.totalDuration());
            }
            return this;
        }

        public AgentAssert costLessThan(double usd) {
            if (execution.totalCostUsd() >= usd) {
                throw new AssertionError(
                        "Expected cost < $" + usd + " but was $" + execution.totalCostUsd());
            }
            return this;
        }

        public AgentAssert usedTool(String toolName) {
            boolean found = execution.events().stream()
                    .filter(RecordedExecution::isToolCall)
                    .anyMatch(e -> toolName.equals(toolNameOf(e)));
            if (!found) {
                throw new AssertionError(
                        "Expected tool '" + toolName + "' to be used but it was not found in execution events");
            }
            return this;
        }

        public AgentAssert usedToolTimes(String toolName, int expectedTimes) {
            long actual = execution.events().stream()
                    .filter(RecordedExecution::isToolCall)
                    .filter(e -> toolName.equals(toolNameOf(e)))
                    .count();
            if (actual != expectedTimes) {
                throw new AssertionError(
                        "Expected tool '" + toolName + "' to be used " + expectedTimes
                                + " times but was used " + actual + " times");
            }
            return this;
        }

        public AgentAssert didNotUseTool(String toolName) {
            boolean found = execution.events().stream()
                    .filter(RecordedExecution::isToolCall)
                    .anyMatch(e -> toolName.equals(toolNameOf(e)));
            if (found) {
                throw new AssertionError(
                        "Expected tool '" + toolName + "' to NOT be used but it was found in execution events");
            }
            return this;
        }

        public AgentAssert toolCallCount(Matcher<Integer> matcher) {
            org.hamcrest.MatcherAssert.assertThat("Tool call count", execution.toolCallCount(), matcher);
            return this;
        }

        public AgentAssert nodeCount(Matcher<Integer> matcher) {
            org.hamcrest.MatcherAssert.assertThat("Node count", execution.nodes().size(), matcher);
            return this;
        }

        public AgentAssert nodeCompleted(String nodeId) {
            boolean found = execution.nodes().stream()
                    .anyMatch(n -> nodeId.equals(n.nodeId()) && "completed".equals(n.status()));
            if (!found) {
                throw new AssertionError(
                        "Expected node '" + nodeId + "' to be completed but it was not");
            }
            return this;
        }

        public AgentAssert nodeRetried(String nodeId, int times) {
            var node = execution.nodes().stream()
                    .filter(n -> nodeId.equals(n.nodeId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Node '" + nodeId + "' not found"));
            if (node.retryCount() != times) {
                throw new AssertionError(
                        "Expected node '" + nodeId + "' to be retried " + times
                                + " times but was retried " + node.retryCount() + " times");
            }
            return this;
        }

        public AgentAssert outputContains(String substring) {
            String output = extractOutput();
            if (!output.contains(substring)) {
                throw new AssertionError(
                        "Expected output to contain '" + substring + "' but output was: " + truncate(output));
            }
            return this;
        }

        public AgentAssert outputMatches(Pattern regex) {
            String output = extractOutput();
            if (!regex.matcher(output).find()) {
                throw new AssertionError(
                        "Expected output to match " + regex + " but output was: " + truncate(output));
            }
            return this;
        }

        public AgentAssert outputSatisfies(java.util.function.Consumer<String> assertion) {
            assertion.accept(extractOutput());
            return this;
        }

        /**
         * Asserts an event of this kind exists. Takes the runtime's snake_case
         * tag ({@code node_failed}); a PascalCase name ({@code NodeFailed}) is
         * converted.
         */
        public AgentAssert hasEvent(String eventType) {
            String wanted = toSnakeCase(eventType);
            boolean found = execution.events().stream()
                    .anyMatch(e -> wanted.equals(e.type()));
            if (!found) {
                throw new AssertionError(
                        "Expected event type '" + eventType + "' but it was not found");
            }
            return this;
        }

        public AgentAssert eventCount(Matcher<Integer> matcher) {
            org.hamcrest.MatcherAssert.assertThat("Event count", execution.events().size(), matcher);
            return this;
        }

        public AgentAssert auditTrailContains(String eventType) {
            return hasEvent(eventType);
        }

        public AgentAssert auditTrailSize(Matcher<Integer> matcher) {
            return eventCount(matcher);
        }

        @SuppressWarnings("unchecked")
        private String extractOutput() {
            if (execution.finalState() instanceof Map<?, ?> stateMap) {
                Object output = stateMap.get("output");
                if (output != null) return output.toString();
            }
            for (int i = execution.events().size() - 1; i >= 0; i--) {
                var event = execution.events().get(i);
                if ("node_completed".equals(event.type())) {
                    Object output = event.field("output");
                    if (output != null) return output.toString();
                }
            }
            return "";
        }

        private static Object toolNameOf(dev.jamjet.spring.client.model.ExecutionEvent e) {
            Object tool = e.field("tool");
            return tool != null ? tool : e.field("tool_name");
        }

        static String toSnakeCase(String name) {
            if (name == null || name.isEmpty() || name.contains("_") || name.equals(name.toLowerCase())) {
                return name;
            }
            return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        }

        private static String truncate(String s) {
            return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        }
    }
}
