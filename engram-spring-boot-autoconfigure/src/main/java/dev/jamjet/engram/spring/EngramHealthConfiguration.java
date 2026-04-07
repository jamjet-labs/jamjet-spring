package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Health indicator auto-configuration for Engram.
 *
 * <p>Only activates when Spring Boot Actuator is on the classpath and
 * {@code engram.health.enabled=true} (default). Separated from the main
 * auto-configuration so applications without Actuator can still use Engram.
 */
@AutoConfiguration(after = EngramAutoConfiguration.class)
@ConditionalOnClass({EngramClient.class, HealthIndicator.class})
@EnableConfigurationProperties(EngramProperties.class)
@ConditionalOnProperty(prefix = "engram.health", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class EngramHealthConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "engramHealthIndicator")
    public HealthIndicator engramHealthIndicator(EngramClient client) {
        return new EngramHealthIndicator(client);
    }
}
