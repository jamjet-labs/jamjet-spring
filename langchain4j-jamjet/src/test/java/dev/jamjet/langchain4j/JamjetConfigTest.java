package dev.jamjet.langchain4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetConfigTest {

    @Test
    void defaultConfigValues() {
        var config = new JamjetConfig();
        assertThat(config.getRuntimeUrl()).isEqualTo("http://localhost:7700");
        assertThat(config.getTenantId()).isEqualTo("default");
        assertThat(config.getApiToken()).isNull();
        assertThat(config.getConnectTimeoutSeconds()).isEqualTo(10);
        assertThat(config.getReadTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void fluentSettersReturnThis() {
        var config = new JamjetConfig()
                .runtimeUrl("http://jamjet:7700")
                .apiToken("my-token")
                .tenantId("acme")
                .connectTimeout(30)
                .readTimeout(60);

        assertThat(config.getRuntimeUrl()).isEqualTo("http://jamjet:7700");
        assertThat(config.getApiToken()).isEqualTo("my-token");
        assertThat(config.getTenantId()).isEqualTo("acme");
        assertThat(config.getConnectTimeoutSeconds()).isEqualTo(30);
        assertThat(config.getReadTimeoutSeconds()).isEqualTo(60);
    }

    @Test
    void buildsClient() {
        var client = new JamjetConfig()
                .runtimeUrl("http://jamjet:7700")
                .buildClient();

        assertThat(client).isNotNull();
    }
}
