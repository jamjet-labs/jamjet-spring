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

    /**
     * Spring AI integration configuration.
     */
    private final SpringAi springAi = new SpringAi();

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

    public SpringAi getSpringAi() {
        return springAi;
    }

    public static class SpringAi {

        /**
         * When {@code true} (default), Engram auto-registers a
         * {@code ChatClientCustomizer} that attaches the
         * {@link EngramContextAdvisor} to every ChatClient built via
         * Spring's default builder. Set to {@code false} to wire the
         * advisor manually.
         */
        private boolean autoWire = true;

        public boolean isAutoWire() {
            return autoWire;
        }

        public void setAutoWire(boolean autoWire) {
            this.autoWire = autoWire;
        }

        /**
         * When {@code true} (default), Engram auto-registers a
         * {@link EngramChatMemoryRepository} as the
         * {@link org.springframework.ai.chat.memory.ChatMemoryRepository} bean.
         * Set to {@code false} to provide your own implementation.
         */
        private boolean chatMemoryRepository = true;

        public boolean isChatMemoryRepository() {
            return chatMemoryRepository;
        }

        public void setChatMemoryRepository(boolean chatMemoryRepository) {
            this.chatMemoryRepository = chatMemoryRepository;
        }
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
