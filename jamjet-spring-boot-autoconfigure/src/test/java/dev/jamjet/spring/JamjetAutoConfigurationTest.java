package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class));

    @Test
    void registersRuntimeClientBean() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(JamjetRuntimeClient.class);
        });
    }

    @Test
    void registersAdvisorBean() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(JamjetDurabilityAdvisor.class);
        });
    }

    @Test
    void defaultPropertiesApplied() {
        runner.run(context -> {
            var props = context.getBean(JamjetProperties.class);
            assertThat(props.getRuntimeUrl()).isEqualTo("http://localhost:7700");
            assertThat(props.getTenantId()).isEqualTo("default");
            assertThat(props.isDurabilityEnabled()).isTrue();
        });
    }

    @Test
    void customPropertiesApplied() {
        runner.withPropertyValues(
                "spring.jamjet.runtime-url=http://jamjet.prod:7700",
                "spring.jamjet.tenant-id=acme",
                "spring.jamjet.api-token=test-token",
                "spring.jamjet.connect-timeout-seconds=30"
        ).run(context -> {
            var props = context.getBean(JamjetProperties.class);
            assertThat(props.getRuntimeUrl()).isEqualTo("http://jamjet.prod:7700");
            assertThat(props.getTenantId()).isEqualTo("acme");
            assertThat(props.getApiToken()).isEqualTo("test-token");
            assertThat(props.getConnectTimeoutSeconds()).isEqualTo(30);
        });
    }

    @Test
    void disabledWhenDurabilityOff() {
        runner.withPropertyValues("spring.jamjet.durability-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JamjetDurabilityAdvisor.class);
                });
    }
}
