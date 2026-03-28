package dev.jamjet.spring.client;

import dev.jamjet.spring.JamjetProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetRuntimeClientTest {

    @Test
    void healthCheckReturnsFalseWhenRuntimeDown() {
        var props = new JamjetProperties();
        props.setRuntimeUrl("http://localhost:19999"); // no server here
        props.setConnectTimeoutSeconds(1);
        props.setReadTimeoutSeconds(1);

        var client = new JamjetRuntimeClient(props);
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void clientCreatesWithDefaults() {
        var props = new JamjetProperties();
        var client = new JamjetRuntimeClient(props);
        // Should not throw
        assertThat(client).isNotNull();
        client.close();
    }
}
