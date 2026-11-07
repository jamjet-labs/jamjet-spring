package dev.jamjet.engram.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Picks the bearer token the starter sends to the Engram server.
 *
 * <p>Order: {@code engram.api-token}, then {@code ENGRAM_AUTH_TOKEN} (the
 * name Engram uses for its server token), then the deprecated
 * {@code ENGRAM_TOKEN}. Blank values count as unset. Variables are read
 * through the Spring {@link Environment}, which includes the process
 * environment.
 */
final class EngramTokens {

    static final String AUTH_TOKEN_ENV = "ENGRAM_AUTH_TOKEN";
    static final String DEPRECATED_TOKEN_ENV = "ENGRAM_TOKEN";

    private static final Logger log = LoggerFactory.getLogger(EngramTokens.class);

    private EngramTokens() {}

    static String resolve(EngramProperties properties, Environment environment) {
        String configured = properties.getApiToken();
        if (hasText(configured)) {
            return configured;
        }
        String token = environment.getProperty(AUTH_TOKEN_ENV);
        if (hasText(token)) {
            return token;
        }
        String deprecated = environment.getProperty(DEPRECATED_TOKEN_ENV);
        if (hasText(deprecated)) {
            log.warn("Engram: ENGRAM_TOKEN is deprecated and will stop working in a later release. "
                    + "Set ENGRAM_AUTH_TOKEN (the name Engram uses for its server token) or engram.api-token instead.");
            return deprecated;
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
