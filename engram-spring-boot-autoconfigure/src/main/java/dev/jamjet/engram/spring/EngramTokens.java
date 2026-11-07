package dev.jamjet.engram.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Picks the bearer token the starter sends to the Engram server.
 *
 * <p>Order: {@code engram.api-token}, then {@code ENGRAM_AUTH_TOKEN} (the
 * name Engram uses for its server token), then the deprecated
 * {@code ENGRAM_TOKEN}. Blank values count as unset and values are trimmed.
 * Variables are read
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
            return checked(configured, "engram.api-token");
        }
        String token = environment.getProperty(AUTH_TOKEN_ENV);
        if (hasText(token)) {
            return checked(token, AUTH_TOKEN_ENV);
        }
        String deprecated = environment.getProperty(DEPRECATED_TOKEN_ENV);
        if (hasText(deprecated)) {
            log.warn("Engram: ENGRAM_TOKEN is deprecated and will stop working in a later release. "
                    + "Set ENGRAM_AUTH_TOKEN (the name Engram uses for its server token) or engram.api-token instead.");
            return checked(deprecated, DEPRECATED_TOKEN_ENV);
        }
        return null;
    }

    /**
     * Trims the token (a value read from a file usually ends in a newline) and
     * refuses one that still holds a control character. Never puts the value
     * in the message: an HTTP header error would print it whole.
     */
    private static String checked(String token, String source) {
        String trimmed = token.strip();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalStateException("Engram: the token from " + source
                        + " contains a control character (at position " + i + "), so it cannot be sent "
                        + "as an HTTP header. Check how the value is set; it is not shown here.");
            }
        }
        return trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
