package dev.jamjet.spring.test;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.test.annotations.ReplayExecution;
import dev.jamjet.spring.test.annotations.WithJamjetRuntime;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JamjetReplayExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, ParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(JamjetReplayExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(JamjetReplayExtension.class);

    private static final String CONTAINER_KEY = "container";
    private static final String CLIENT_KEY = "client";
    private static final String EXECUTION_KEY = "execution";

    @Override
    public void beforeAll(ExtensionContext context) {
        var withRuntime = context.getRequiredTestClass().getAnnotation(WithJamjetRuntime.class);
        if (withRuntime != null) {
            var container = new JamjetTestContainer(withRuntime.image(), withRuntime.tag());
            container.start();
            log.info("Started JamJet test container at {}", container.getRuntimeUrl());

            var props = new JamjetProperties();
            props.setRuntimeUrl(container.getRuntimeUrl());
            var client = new JamjetRuntimeClient(props);

            var store = context.getStore(NAMESPACE);
            store.put(CONTAINER_KEY, container);
            store.put(CLIENT_KEY, client);
        } else {
            String runtimeUrl = System.getProperty("spring.jamjet.runtime-url", "http://localhost:7700");
            var props = new JamjetProperties();
            props.setRuntimeUrl(runtimeUrl);
            var client = new JamjetRuntimeClient(props);
            context.getStore(NAMESPACE).put(CLIENT_KEY, client);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        var store = context.getStore(NAMESPACE);
        var container = store.get(CONTAINER_KEY, JamjetTestContainer.class);
        if (container != null) {
            container.stop();
            log.info("Stopped JamJet test container");
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        var replay = context.getRequiredTestMethod().getAnnotation(ReplayExecution.class);
        if (replay == null) {
            return;
        }

        var client = getClient(context);
        String executionId = replay.value();
        String forkAtNode = replay.forkAtNode();

        log.debug("Replaying execution {}{}", executionId,
                forkAtNode.isEmpty() ? "" : " (fork at " + forkAtNode + ")");

        var state = client.getExecution(executionId);
        var events = client.listEvents(executionId);
        var recorded = RecordedExecution.fromStateAndEvents(state, events);

        if (!forkAtNode.isEmpty()) {
            recorded = recorded.forkAt(forkAtNode);
        }

        context.getStore(NAMESPACE).put(EXECUTION_KEY, recorded);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == RecordedExecution.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE).get(EXECUTION_KEY, RecordedExecution.class);
    }

    private JamjetRuntimeClient getClient(ExtensionContext context) {
        var store = context.getStore(NAMESPACE);
        var client = store.get(CLIENT_KEY, JamjetRuntimeClient.class);
        if (client != null) return client;

        return context.getParent()
                .map(parent -> parent.getStore(NAMESPACE).get(CLIENT_KEY, JamjetRuntimeClient.class))
                .orElseThrow(() -> new IllegalStateException(
                        "No JamjetRuntimeClient found. Use @WithJamjetRuntime or set spring.jamjet.runtime-url system property."));
    }
}
