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
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for Engram — a durable memory layer for AI agents.
 *
 * <p>Wires an {@link EngramClient} bean from {@link EngramProperties} and
 * (optionally) an {@link EngramHealthIndicator} for Spring Boot Actuator.
 *
 * <h2>Minimum configuration</h2>
 *
 * Add the starter dependency. The Rust Engram REST server
 * (jamjet-engram-server) must be running on {@code http://localhost:9090}
 * (the default) or set {@code engram.base-url} to point somewhere else. The
 * Python Engram server serves different routes and is not supported; at
 * startup the starter checks {@code GET /health} and refuses to start against
 * a server that is not the Rust one (see {@code engram.verify-on-startup}).
 * Start the server with:
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
 *   api-token: ${ENGRAM_AUTH_TOKEN:} # optional; ENGRAM_AUTH_TOKEN is also read directly
 *   timeout-seconds: 30
 *   verify-on-startup: true    # default true
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
    public EngramClient engramClient(EngramProperties properties, Environment environment) {
        log.info("Configuring Engram memory client -> {}", properties.getBaseUrl());
        String token = EngramTokens.resolve(properties, environment);
        EngramConfig config = EngramConfig.builder()
                .baseUrl(properties.getBaseUrl())
                .apiToken(token)
                .timeoutSeconds(properties.getTimeoutSeconds())
                .build();
        if (config.apiToken() != null && sendsInTheClear(config.baseUrl())) {
            log.warn("Engram: sending the Engram token over plain HTTP to {}. Use https unless the "
                    + "network between this application and Engram is private.", config.baseUrl());
        }
        if (properties.isVerifyOnStartup()) {
            new EngramServerCheck(config.baseUrl(), config.apiToken(), properties.getTimeoutSeconds()).verify();
        }
        return new EngramClient(config);
    }

    private static boolean sendsInTheClear(String baseUrl) {
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost();
            return !(host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("[::1]"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
