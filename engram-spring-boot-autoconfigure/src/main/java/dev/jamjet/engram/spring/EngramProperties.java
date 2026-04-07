package dev.jamjet.engram.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Engram memory layer.
 *
 * <p>Bound to the {@code engram.*} property prefix. Example
 * {@code application.yml}:
 *
 * <pre>{@code
 * engram:
 *   enabled: true
 *   base-url: http://localhost:9090
 *   api-token: ${ENGRAM_TOKEN:}
 *   timeout-seconds: 30
 *   default-user-id: alice
 * }</pre>
 */
@ConfigurationProperties(prefix = "engram")
public class EngramProperties {

    /**
     * Whether Engram auto-configuration is enabled. Default: {@code true}.
     */
    private boolean enabled = true;

    /**
     * Base URL of the Engram REST server. Default: {@code http://localhost:9090}.
     * Run the server via {@code engram serve --mode rest --port 9090}.
     */
    private String baseUrl = "http://localhost:9090";

    /**
     * Optional API token for authenticated Engram servers. Falls back to
     * the {@code ENGRAM_TOKEN} environment variable when unset.
     */
    private String apiToken;

    /**
     * HTTP request timeout in seconds. Default: {@code 30}.
     */
    private int timeoutSeconds = 30;

    /**
     * Optional default user identifier applied to recall and context calls
     * when the caller does not specify one. Useful for single-tenant apps.
     */
    private String defaultUserId;

    /**
     * Optional default organisation identifier applied to recall and context
     * calls when the caller does not specify one.
     */
    private String defaultOrgId = "default";

    /**
     * Health check configuration.
     */
    private final Health health = new Health();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    public String getDefaultOrgId() {
        return defaultOrgId;
    }

    public void setDefaultOrgId(String defaultOrgId) {
        this.defaultOrgId = defaultOrgId;
    }

    public Health getHealth() {
        return health;
    }

    public static class Health {

        /**
         * Whether the Engram health indicator is enabled. Default: {@code true}.
         * Requires Spring Boot Actuator on the classpath.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
