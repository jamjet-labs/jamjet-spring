package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;

/**
 * Spring Boot Actuator health indicator for Engram.
 *
 * <p>Calls the Engram server's {@code /health} endpoint. Contributes to
 * {@code /actuator/health} under the {@code engram} key.
 */
public class EngramHealthIndicator implements HealthIndicator {

    private final EngramClient client;

    public EngramHealthIndicator(EngramClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> result = client.health();
            String status = String.valueOf(result.getOrDefault("status", "unknown"));
            if ("ok".equalsIgnoreCase(status)) {
                return Health.up()
                        .withDetail("service", result.getOrDefault("service", "engram"))
                        .build();
            }
            return Health.down()
                    .withDetail("status", status)
                    .withDetail("response", result)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("exception", e.getClass().getSimpleName())
                    .build();
        }
    }
}
