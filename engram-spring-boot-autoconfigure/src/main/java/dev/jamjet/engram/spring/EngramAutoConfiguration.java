package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import dev.jamjet.engram.EngramConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Engram — a durable memory layer for AI agents.
 *
 * <p>Wires an {@link EngramClient} bean from {@link EngramProperties} and
 * (optionally) an {@link EngramHealthIndicator} for Spring Boot Actuator.
 *
 * <h2>Minimum configuration</h2>
 *
 * Add the starter dependency. An Engram REST server must be running on
 * {@code http://localhost:9090} (the default) or set
 * {@code engram.base-url} to point somewhere else. Start the server with:
 *
 * <pre>{@code
 * cargo install jamjet-engram-server
 * engram serve --mode rest --port 9090 --db ~/.engram/memory.db
 * }</pre>
 *
 * <h2>Properties</h2>
 *
 * <pre>{@code
 * engram:
 *   enabled: true              # default true
 *   base-url: http://localhost:9090
 *   api-token: ${ENGRAM_TOKEN:} # optional
 *   timeout-seconds: 30
 *   default-user-id: alice     # optional
 *   default-org-id: default    # optional
 *   health:
 *     enabled: true
 * }</pre>
 *
 * <h2>Injecting the client</h2>
 *
 * <pre>{@code
 * @Service
 * public class MyAssistant {
 *     private final EngramClient memory;
 *
 *     public MyAssistant(EngramClient memory) {
 *         this.memory = memory;
 *     }
 *
 *     public String respond(String prompt, String userId) {
 *         var context = memory.context(prompt, userId, null, 1000, "system_prompt");
 *         // ... pass context.get("text") into your prompt
 *     }
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(EngramClient.class)
@EnableConfigurationProperties(EngramProperties.class)
@ConditionalOnProperty(prefix = "engram", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class EngramAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngramAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public EngramClient engramClient(EngramProperties properties) {
        log.info("Configuring Engram memory client -> {}", properties.getBaseUrl());
        EngramConfig config = EngramConfig.builder()
                .baseUrl(properties.getBaseUrl())
                .apiToken(properties.getApiToken())
                .timeoutSeconds(properties.getTimeoutSeconds())
                .build();
        return new EngramClient(config);
    }
}
