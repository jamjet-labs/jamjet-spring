package dev.jamjet.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JamJet Spring Boot integration.
 *
 * <pre>{@code
 * spring.jamjet.runtime-url=http://localhost:7700
 * spring.jamjet.api-token=${JAMJET_API_TOKEN}
 * spring.jamjet.durability-enabled=true
 * spring.jamjet.on-runtime-error=fail-closed
 * }</pre>
 */
@ConfigurationProperties(prefix = "spring.jamjet")
public class JamjetProperties {

    /** JamJet runtime URL. */
    private String runtimeUrl = "http://localhost:7700";

    /** API token for authentication. */
    private String apiToken;

    /**
     * Ignored. The runtime takes the tenant from the API token ({@code default}
     * in dev mode) and never read the {@code X-Tenant-Id} header this used to send.
     *
     * @deprecated the API token decides the tenant; setting this logs a warning
     */
    @Deprecated
    private String tenantId;

    /** Enable/disable durable execution. */
    private boolean durabilityEnabled = true;

    /**
     * What the advisors do when the runtime cannot record a call:
     * {@code fail-closed} (default) refuses the call, {@code fail-open} logs a
     * warning and lets it run unrecorded.
     */
    private RuntimeErrorMode onRuntimeError = RuntimeErrorMode.FAIL_CLOSED;

    /**
     * Workflow id the durability advisor registers on the runtime. Every
     * advised call starts one execution of this workflow.
     */
    private String workflowId = "spring-ai-chat";

    /**
     * Allow streaming ChatClient calls in fail-closed mode. Off by default: a
     * stream's record can only be written after its output was delivered, so a
     * record failure on a stream is reported after the fact.
     */
    private boolean allowStreaming = false;

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

    /** @deprecated ignored; the API token decides the tenant. */
    @Deprecated
    public String getTenantId() {
        return tenantId;
    }

    /** @deprecated ignored; the API token decides the tenant. */
    @Deprecated
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public boolean isDurabilityEnabled() {
        return durabilityEnabled;
    }

    public void setDurabilityEnabled(boolean durabilityEnabled) {
        this.durabilityEnabled = durabilityEnabled;
    }

    public RuntimeErrorMode getOnRuntimeError() {
        return onRuntimeError;
    }

    public void setOnRuntimeError(RuntimeErrorMode onRuntimeError) {
        this.onRuntimeError = onRuntimeError == null ? RuntimeErrorMode.FAIL_CLOSED : onRuntimeError;
    }

    public boolean isFailOpen() {
        return onRuntimeError == RuntimeErrorMode.FAIL_OPEN;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public boolean isAllowStreaming() {
        return allowStreaming;
    }

    public void setAllowStreaming(boolean allowStreaming) {
        this.allowStreaming = allowStreaming;
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

        /**
         * Deprecated: a call that times out is always rejected. Only "rejected" is
         * accepted; any other value fails at startup.
         */
        @Deprecated
        private String defaultDecision = "rejected";

        /** Accept a remember-me login as a reviewer. Off: a reviewer must have signed in this session. */
        private boolean allowRememberMe = false;

        public boolean isAllowRememberMe() {
            return allowRememberMe;
        }

        public void setAllowRememberMe(boolean allowRememberMe) {
            this.allowRememberMe = allowRememberMe;
        }

        /** The approval REST endpoints ({@code spring.jamjet.approval.controller.*}). */
        private Controller controller = new Controller();

        public Controller getController() {
            return controller;
        }

        public void setController(Controller controller) {
            this.controller = controller;
        }

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

        /** @deprecated a timed-out call is always rejected. */
        @Deprecated
        public String getDefaultDecision() {
            return defaultDecision;
        }

        /** @deprecated a timed-out call is always rejected; only "rejected" is accepted. */
        @Deprecated
        public void setDefaultDecision(String defaultDecision) {
            this.defaultDecision = defaultDecision;
        }
    }

    /**
     * Approval endpoint configuration ({@code spring.jamjet.approval.controller.*}).
     */
    public static class Controller {

        /**
         * Register the approval endpoints without Spring Security, accepting
         * unauthenticated decisions. Local development only: anyone who can reach
         * the application can then approve its calls.
         */
        private boolean allowUnauthenticated = false;

        public boolean isAllowUnauthenticated() {
            return allowUnauthenticated;
        }

        public void setAllowUnauthenticated(boolean allowUnauthenticated) {
            this.allowUnauthenticated = allowUnauthenticated;
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
