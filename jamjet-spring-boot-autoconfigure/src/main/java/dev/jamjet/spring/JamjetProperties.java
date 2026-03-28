package dev.jamjet.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JamJet Spring Boot integration.
 *
 * <pre>{@code
 * spring.jamjet.runtime-url=http://localhost:7700
 * spring.jamjet.api-token=${JAMJET_API_TOKEN}
 * spring.jamjet.tenant-id=default
 * spring.jamjet.durability-enabled=true
 * }</pre>
 */
@ConfigurationProperties(prefix = "spring.jamjet")
public class JamjetProperties {

    /** JamJet runtime URL. */
    private String runtimeUrl = "http://localhost:7700";

    /** API token for authentication. */
    private String apiToken;

    /** Tenant ID for multi-tenant isolation. */
    private String tenantId = "default";

    /** Enable/disable durable execution. */
    private boolean durabilityEnabled = true;

    /** Connection timeout in seconds. */
    private int connectTimeoutSeconds = 10;

    /** Read timeout in seconds. */
    private int readTimeoutSeconds = 120;

    public String getRuntimeUrl() {
        return runtimeUrl;
    }

    public void setRuntimeUrl(String runtimeUrl) {
        this.runtimeUrl = runtimeUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public boolean isDurabilityEnabled() {
        return durabilityEnabled;
    }

    public void setDurabilityEnabled(boolean durabilityEnabled) {
        this.durabilityEnabled = durabilityEnabled;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }
}
