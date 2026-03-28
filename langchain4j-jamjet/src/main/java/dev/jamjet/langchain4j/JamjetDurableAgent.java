package dev.jamjet.langchain4j;

import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;

public final class JamjetDurableAgent {

    private JamjetDurableAgent() {}

    @SuppressWarnings("unchecked")
    public static <T> T wrap(T aiService, Class<T> iface, JamjetRuntimeClient client) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{ iface },
                new DurabilityHandler<>(aiService, client));
    }

    private static class DurabilityHandler<T> implements InvocationHandler {

        private static final Logger log = LoggerFactory.getLogger(DurabilityHandler.class);

        private final T delegate;
        private final JamjetRuntimeClient client;

        DurabilityHandler(T delegate, JamjetRuntimeClient client) {
            this.delegate = delegate;
            this.client = client;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            String executionId;
            try {
                var ir = buildIr(method);
                var wfResp = client.createWorkflow(ir);
                var execResp = client.startExecution(wfResp.workflowId(), null,
                        Map.of("method", method.getName(),
                               "args", args != null ? Arrays.stream(args).map(String::valueOf).toList() : java.util.List.of()));
                executionId = execResp.executionId();
            } catch (JamjetClientException e) {
                log.warn("JamJet runtime unavailable, proceeding without durability: {}", e.getMessage());
                return invokeDelegate(method, args);
            }

            try {
                Object result = invokeDelegate(method, args);

                try {
                    client.sendExternalEvent(executionId, "completion",
                            Map.of("status", "completed", "result", String.valueOf(result)));
                } catch (JamjetClientException e) {
                    log.warn("Failed to record completion for execution {}: {}", executionId, e.getMessage());
                }

                return result;
            } catch (Throwable t) {
                try {
                    client.sendExternalEvent(executionId, "completion",
                            Map.of("status", "failed", "error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                } catch (JamjetClientException e) {
                    log.warn("Failed to record failure for execution {}: {}", executionId, e.getMessage());
                }
                throw t;
            }
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private Map<String, Object> buildIr(Method method) {
            String name = "langchain4j-" + method.getDeclaringClass().getSimpleName() + "-" + method.getName();
            return Map.of(
                    "name", name,
                    "version", "1",
                    "nodes", Map.of("invoke", Map.of("kind", "LlmGenerate", "method", method.getName())),
                    "edges", Map.of(),
                    "entry_node", "invoke");
        }
    }
}
