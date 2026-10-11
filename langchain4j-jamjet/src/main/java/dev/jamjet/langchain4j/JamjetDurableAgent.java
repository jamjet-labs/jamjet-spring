package dev.jamjet.langchain4j;

import dev.jamjet.spring.RuntimeErrorMode;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCallWorkflow;
import dev.jamjet.spring.client.model.StartExecutionResponse;
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
 * workflow is registered once, each call starts an execution of it, and a
 * {@code langchain4j.completed} external event records the outcome.
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

    static Map<String, Object> workflowIr() {
        return RecordedCallWorkflow.ir(WORKFLOW_ID, "invoke", COMPLETED_EVENT,
                "LangChain4j AI service call", "langchain4j-jamjet");
    }

    private static class DurabilityHandler<T> implements InvocationHandler {

        private static final Logger log = LoggerFactory.getLogger(JamjetDurableAgent.class);

        private final T delegate;
        private final JamjetRuntimeClient client;
        private final RuntimeErrorMode mode;
        private final boolean recordContent;
        private volatile boolean registered;

        DurabilityHandler(T delegate, JamjetRuntimeClient client, RuntimeErrorMode mode, boolean recordContent) {
            this.delegate = delegate;
            this.client = client;
            this.mode = mode;
            this.recordContent = recordContent;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            String executionId = null;
            try {
                ensureRegistered();
                var input = new LinkedHashMap<String, Object>();
                input.put("interface", method.getDeclaringClass().getName());
                input.put("method", method.getName());
                if (recordContent) {
                    input.put("args", args != null ? Arrays.stream(args).map(String::valueOf).toList() : List.of());
                } else {
                    input.put("arg_count", args != null ? args.length : 0);
                }
                StartExecutionResponse started;
                try {
                    started = client.startExecution(WORKFLOW_ID, RecordedCallWorkflow.VERSION, input);
                } catch (JamjetClientException e) {
                    if (e.getStatusCode() != 404) {
                        throw e;
                    }
                    // The runtime forgot the workflow (store reset): register again, retry once.
                    registered = false;
                    ensureRegistered();
                    started = client.startExecution(WORKFLOW_ID, RecordedCallWorkflow.VERSION, input);
                }
                executionId = started != null ? started.executionId() : null;
                if (executionId == null || executionId.isBlank()) {
                    throw new JamjetClientException("the runtime returned no execution_id", "/executions");
                }
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

        private void ensureRegistered() {
            if (!registered) {
                synchronized (this) {
                    if (!registered) {
                        client.createWorkflow(workflowIr());
                        registered = true;
                    }
                }
            }
        }

        /** After the call returned: a failure here is a recording failure, never a refusal. */
        private void record(String executionId, Map<String, Object> outcome) {
            try {
                client.sendExternalEvent(executionId, COMPLETED_EVENT, outcome);
            } catch (JamjetClientException e) {
                if (mode == RuntimeErrorMode.FAIL_OPEN) {
                    log.warn("The call completed but its outcome could not be recorded: {}", e.getMessage());
                    return;
                }
                throw Failures.recordingFailure("record the outcome of execution " + executionId, e);
            }
        }

        /** The call threw: keep its error, attach a recording failure as suppressed. */
        private void recordFailure(String executionId, Throwable callError) {
            try {
                String error = recordContent && callError.getMessage() != null
                        ? callError.getMessage() : callError.getClass().getSimpleName();
                client.sendExternalEvent(executionId, COMPLETED_EVENT, Map.of("status", "failed", "error", error));
            } catch (JamjetClientException e) {
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
