package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EngramAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EngramAutoConfiguration.class));

    @Test
    void engramClientIsRegisteredByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EngramClient.class);
            assertThat(context).hasSingleBean(EngramProperties.class);
        });
    }

    @Test
    void engramClientIsNotRegisteredWhenDisabled() {
        contextRunner
                .withPropertyValues("engram.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EngramClient.class);
                });
    }

    @Test
    void customBaseUrlIsApplied() {
        contextRunner
                .withPropertyValues("engram.base-url=http://custom:8080")
                .run(context -> {
                    assertThat(context).hasSingleBean(EngramClient.class);
                    EngramProperties props = context.getBean(EngramProperties.class);
                    assertThat(props.getBaseUrl()).isEqualTo("http://custom:8080");
                });
    }

    @Test
    void customTimeoutIsApplied() {
        contextRunner
                .withPropertyValues("engram.timeout-seconds=60")
                .run(context -> {
                    EngramProperties props = context.getBean(EngramProperties.class);
                    assertThat(props.getTimeoutSeconds()).isEqualTo(60);
                });
    }

    @Test
    void defaultPropertiesApplied() {
        contextRunner.run(context -> {
            EngramProperties props = context.getBean(EngramProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getBaseUrl()).isEqualTo("http://localhost:9090");
            assertThat(props.getTimeoutSeconds()).isEqualTo(30);
            assertThat(props.getDefaultOrgId()).isEqualTo("default");
            assertThat(props.getHealth().isEnabled()).isTrue();
        });
    }

    @Test
    void userProvidedEngramClientTakesPrecedence() {
        contextRunner
                .withBean(EngramClient.class, () -> new EngramClient(
                        dev.jamjet.engram.EngramConfig.builder().baseUrl("http://override:7777").build()))
                .run(context -> {
                    assertThat(context).hasSingleBean(EngramClient.class);
                });
    }
}
