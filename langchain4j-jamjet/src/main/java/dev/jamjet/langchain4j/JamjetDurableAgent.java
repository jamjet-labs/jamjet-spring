package dev.jamjet.langchain4j;

import dev.jamjet.spring.RuntimeErrorMode;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a LangChain4j AI service so every method call is recorded as an
 * execution on the JamJet runtime: the {@code langchain4j-durable-agent}
 * workflow is registered once, each call starts an execution of it and claims
 * its node, and a {@code langchain4j.completed} external event records the
 * outcome, which then completes (or, for a failed call, fails) the node. See
 * {@link RecordedCalls}.
 *
 * <p>The call itself runs in this JVM; the runtime holds the record. By default
 * a call the runtime cannot record is refused
 * ({@link dev.jamjet.spring.JamjetGovernanceException}); pass
 * {@link RuntimeErrorMode#FAIL_OPEN} to run it anyway.
 */
public final class JamjetDurableAgent {

    /** Workflow id every wrapped call runs under. */
    public static final String WORKFLOW_ID = "langchain4j-durable-agent";

    /** Correlation key of the outcome event. */
    public static final String COMPLETED_EVENT = "langchain4j.completed";

    private JamjetDurableAgent() {}

    /** Wrap with the default, fail-closed error mode. */
    public static <T> T wrap(T aiService, Class<T> iface, JamjetRuntimeClient client) {
        return wrap(aiService, iface, client, RuntimeErrorMode.FAIL_CLOSED);
    }

    public static <T> T wrap(T aiService, Class<T> iface, JamjetRuntimeClient client, RuntimeErrorMode mode) {
        return wrap(aiService, iface, client, mode, true);
    }

    /**
     * @param recordContent record the call's arguments and result (default {@code true}).
     *     With {@code false} only the method, the argument count, the outcome and,
     *     on failure, the exception type are recorded.
     */
    @SuppressWarnings("unchecked")
    public static <T> T wrap(T aiService, Class<T> iface, JamjetRuntimeClient client, RuntimeErrorMode mode,
                             boolean recordContent) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{ iface },
                new DurabilityHandler<>(aiService, client, mode == null ? RuntimeErrorMode.FAIL_CLOSED : mode,
                        recordContent));
    }

    static RecordedCalls.Spec spec() {
        return RecordedCalls.Spec.call(WORKFLOW_ID, "invoke", COMPLETED_EVENT,
                "LangChain4j AI service call", "langchain4j-jamjet");
    }

    static Map<String, Object> workflowIr() {
        return spec().ir();
    }

    static Map<String, Object> legacyWorkflowIr() {
        return spec().legacyIr();
    }

    private static class DurabilityHandler<T> implements InvocationHandler {

        private static final Logger log = LoggerFactory.getLogger(JamjetDurableAgent.class);

        private final T delegate;
        private final JamjetRuntimeClient client;
        private final RuntimeErrorMode mode;
        private final boolean recordContent;
        private final RecordedCalls calls;

        DurabilityHandler(T delegate, JamjetRuntimeClient client, RuntimeErrorMode mode, boolean recordContent) {
            this.delegate = delegate;
            this.client = client;
            this.mode = mode;
            this.recordContent = recordContent;
            // Shared by every proxy on this runtime, so many wraps do not mean many threads.
            this.calls = RecordedCalls.shared(client, spec());
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            String executionId;
            try {
                var input = new LinkedHashMap<String, Object>();
                input.put("interface", method.getDeclaringClass().getName());
                input.put("method", method.getName());
                if (recordContent) {
                    input.put("args", args != null ? Arrays.stream(args).map(String::valueOf).toList() : List.of());
                } else {
                    input.put("arg_count", args != null ? args.length : 0);
                }
                // Registers the workflow (again if the runtime forgot it), starts an
                // execution and claims its node. A runtime that refuses the call under
                // its policy throws JamjetGovernanceException, whatever the mode.
                executionId = calls.start(input);
            } catch (JamjetClientException e) {
                Failures.handle(mode, log, "start an execution for " + method.getName(), e);
                return invokeDelegate(method, args);
            }

            Object result;
            try {
                result = invokeDelegate(method, args);
            } catch (Throwable t) {
                recordFailure(executionId, t);
                throw t;
            }
            record(executionId, recordContent
                    ? Map.of("status", "completed", "result", String.valueOf(result))
                    : Map.of("status", "completed"));
            return result;
        }

        /** After the call returned: a failure here is a recording failure, never a refusal. */
        private void record(String executionId, Map<String, Object> outcome) {
            try {
                client.sendExternalEvent(executionId, COMPLETED_EVENT, outcome);
            } catch (JamjetClientException e) {
                calls.finish(executionId, outcome);
                if (mode == RuntimeErrorMode.FAIL_OPEN) {
                    log.warn("The call completed but its outcome could not be recorded: {}", e.getMessage());
                    return;
                }
                throw Failures.recordingFailure("record the outcome of execution " + executionId, e);
            }
            calls.finish(executionId, outcome);
        }

        /** The call threw: keep its error, attach a recording failure as suppressed. */
        private void recordFailure(String executionId, Throwable callError) {
            String error = recordContent && callError.getMessage() != null
                    ? callError.getMessage() : callError.getClass().getSimpleName();
            Map<String, Object> failure = Map.of("status", "failed", "error", error);
            try {
                client.sendExternalEvent(executionId, COMPLETED_EVENT, failure);
                calls.finish(executionId, failure);
            } catch (JamjetClientException e) {
                calls.finish(executionId, failure);
                if (mode == RuntimeErrorMode.FAIL_OPEN) {
                    log.warn("Could not record the failure of execution {}: {}", executionId, e.getMessage());
                } else {
                    callError.addSuppressed(Failures.recordingFailure(
                            "record the failure of execution " + executionId, e));
                }
            }
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                if (!method.canAccess(delegate)) {
                    // A non-public service interface (or a lambda implementing one)
                    // is not reachable from this package without it.
                    method.setAccessible(true);
                }
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
