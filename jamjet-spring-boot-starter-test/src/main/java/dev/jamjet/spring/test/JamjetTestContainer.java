package dev.jamjet.spring.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A JamJet runtime in a container, for integration tests.
 *
 * <p>Runs the published runtime image ({@code jamjetdev/jamjet} on Docker Hub,
 * built by the jamjet repo's {@code docker-publish.yml}) in dev mode: no auth,
 * in-memory storage, embedded workers. The image serves on port 8080.
 */
public class JamjetTestContainer extends GenericContainer<JamjetTestContainer> {

    public static final String IMAGE = "jamjetdev/jamjet";
    public static final String DEFAULT_TAG = "0.5.0";
    public static final int PORT = 8080;

    public JamjetTestContainer() {
        this(IMAGE, DEFAULT_TAG);
    }

    public JamjetTestContainer(String tag) {
        this(IMAGE, tag);
    }

    public JamjetTestContainer(String image, String tag) {
        super(image + ":" + tag);
        withExposedPorts(PORT);
        withEnv("JAMJET_DEV_MODE", "1");
        withEnv("JAMJET_BIND", "0.0.0.0");
        withEnv("JAMJET_PORT", String.valueOf(PORT));
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
