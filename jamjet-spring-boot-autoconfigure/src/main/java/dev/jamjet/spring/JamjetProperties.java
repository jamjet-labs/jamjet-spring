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

    /** Audit trail configuration. */
    private Audit audit = new Audit();

    /** Human-in-the-loop approval configuration. */
    private Approval approval = new Approval();

    /** Observability configuration. */
    private Observability observability = new Observability();

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

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public Approval getApproval() {
        return approval;
    }

    public void setApproval(Approval approval) {
        this.approval = approval;
    }

    public Observability getObservability() {
        return observability;
    }

    public void setObservability(Observability observability) {
        this.observability = observability;
    }

    /**
     * Audit trail configuration ({@code spring.jamjet.audit.*}).
     */
    public static class Audit {

        /** Enable audit trail logging. */
        private boolean enabled = true;

        /** Include full prompt text in audit entries. */
        private boolean includePrompts = true;

        /** Include full response text in audit entries. */
        private boolean includeResponses = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludePrompts() {
            return includePrompts;
        }

        public void setIncludePrompts(boolean includePrompts) {
            this.includePrompts = includePrompts;
        }

        public boolean isIncludeResponses() {
            return includeResponses;
        }

        public void setIncludeResponses(boolean includeResponses) {
            this.includeResponses = includeResponses;
        }
    }

    /**
     * Human-in-the-loop approval configuration ({@code spring.jamjet.approval.*}).
     */
    public static class Approval {

        /** Enable approval workflow (opt-in). */
        private boolean enabled = false;

        /** External webhook URL for approval notifications (Slack, email, etc.). */
        private String webhookUrl;

        /** Max time to wait for an approval before timing out. */
        private String timeout = "30m";

        /** What happens on timeout: "approved" or "rejected". */
        private String defaultDecision = "rejected";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getTimeout() {
            return timeout;
        }

        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }

        public String getDefaultDecision() {
            return defaultDecision;
        }

        public void setDefaultDecision(String defaultDecision) {
            this.defaultDecision = defaultDecision;
        }
    }

    /**
     * Observability configuration ({@code spring.jamjet.observability.*}).
     */
    public static class Observability {

        /** Enable Micrometer metrics (on by default when actuator present). */
        private boolean micrometer = true;

        /** Enable OpenTelemetry spans (opt-in). */
        private boolean opentelemetry = false;

        /** Metric name prefix. */
        private String metricPrefix = "jamjet";

        public boolean isMicrometer() {
            return micrometer;
        }

        public void setMicrometer(boolean micrometer) {
            this.micrometer = micrometer;
        }

        public boolean isOpentelemetry() {
            return opentelemetry;
        }

        public void setOpentelemetry(boolean opentelemetry) {
            this.opentelemetry = opentelemetry;
        }

        public String getMetricPrefix() {
            return metricPrefix;
        }

        public void setMetricPrefix(String metricPrefix) {
            this.metricPrefix = metricPrefix;
        }
    }
}
