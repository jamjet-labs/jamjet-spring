package dev.jamjet.spring.observability;

import dev.jamjet.spring.JamjetAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class));

    @Test
    void micrometerBridgeRegisteredWhenMeterRegistryPresent() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(JamjetMicrometerBridge.class);
                });
    }

    @Test
    void micrometerBridgeAbsentWhenDisabled() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("spring.jamjet.observability.micrometer=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JamjetMicrometerBridge.class);
                });
    }

    @Test
    void micrometerBridgeAbsentWhenNoMeterRegistry() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(JamjetMicrometerBridge.class);
        });
    }

    @Test
    void otelBridgeAbsentByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(JamjetOtelBridge.class);
        });
    }
}
