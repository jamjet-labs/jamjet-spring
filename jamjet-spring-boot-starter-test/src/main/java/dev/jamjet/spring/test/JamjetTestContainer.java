package dev.jamjet.spring.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.springframework.test.context.DynamicPropertyRegistry;

public class JamjetTestContainer extends GenericContainer<JamjetTestContainer> {

    public static final String IMAGE = "ghcr.io/jamjet-labs/jamjet";
    public static final String DEFAULT_TAG = "0.3.1";
    public static final int PORT = 7700;

    public JamjetTestContainer() {
        this(IMAGE, DEFAULT_TAG);
    }

    public JamjetTestContainer(String tag) {
        this(IMAGE, tag);
    }

    public JamjetTestContainer(String image, String tag) {
        super(image + ":" + tag);
        withExposedPorts(PORT);
        waitingFor(Wait.forHttp("/health")
                .forStatusCode(200)
                .forResponsePredicate(body -> body.contains("\"status\":\"ok\"")));
    }

    public String getRuntimeUrl() {
        return "http://" + getHost() + ":" + getMappedPort(PORT);
    }

    public void applyTo(DynamicPropertyRegistry registry) {
        registry.add("spring.jamjet.runtime-url", this::getRuntimeUrl);
    }
}
