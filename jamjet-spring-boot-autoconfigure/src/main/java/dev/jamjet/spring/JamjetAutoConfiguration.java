package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import dev.jamjet.spring.advisor.JamjetAuditAdvisor;
import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import dev.jamjet.spring.approval.ApprovalWaitRegistry;
import dev.jamjet.spring.approval.JamjetApprovalController;
import dev.jamjet.spring.audit.JamjetAuditService;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for JamJet durable execution with Spring AI.
 *
 * <p>Registers beans based on configuration:
 * <ul>
 *   <li>{@link JamjetRuntimeClient} — always (when durability enabled)</li>
 *   <li>{@link JamjetDurabilityAdvisor} — always (core durability)</li>
 *   <li>a {@link ChatClientCustomizer} adding the enabled advisors to every auto-configured ChatClient</li>
 *   <li>{@link JamjetAuditAdvisor} + {@link JamjetAuditService} — when {@code spring.jamjet.audit.enabled=true} (default)</li>
 *   <li>{@link JamjetApprovalAdvisor} + {@link JamjetApprovalController} — when {@code spring.jamjet.approval.enabled=true} (opt-in)</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(JamjetProperties.class)
@ConditionalOnProperty(prefix = "spring.jamjet", name = "durability-enabled",
                       havingValue = "true", matchIfMissing = true)
public class JamjetAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JamjetAutoConfiguration.class);

    // ── Core ──────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public JamjetRuntimeClient jamjetRuntimeClient(JamjetProperties properties) {
        log.info("Configuring JamJet runtime client -> {}", properties.getRuntimeUrl());
        warnIfTenantIdSet(properties);
        return new JamjetRuntimeClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JamjetDurabilityAdvisor jamjetDurabilityAdvisor(
            JamjetRuntimeClient client, JamjetProperties properties) {
        log.info("Enabling JamJet durable execution advisor");
        return new JamjetDurabilityAdvisor(client, properties);
    }

    /**
     * Adds the JamJet advisors to every auto-configured ChatClient: durability
     * always, audit and approval when their beans are enabled. Each advisor
     * orders itself (durability, then audit, then approval).
     */
    @Bean
    public ChatClientCustomizer jamjetChatClientCustomizer(
            JamjetDurabilityAdvisor durabilityAdvisor,
            ObjectProvider<JamjetAuditAdvisor> auditAdvisor,
            ObjectProvider<JamjetApprovalAdvisor> approvalAdvisor) {
        return builder -> {
            List<Advisor> advisors = new ArrayList<>();
            advisors.add(durabilityAdvisor);
            auditAdvisor.ifAvailable(advisors::add);
            approvalAdvisor.ifAvailable(advisors::add);
            builder.defaultAdvisors(advisors.toArray(Advisor[]::new));
        };
    }

    @SuppressWarnings("deprecation")
    private static void warnIfTenantIdSet(JamjetProperties properties) {
        if (properties.getTenantId() != null && !properties.getTenantId().isBlank()) {
            log.warn("spring.jamjet.tenant-id is ignored: the runtime takes the tenant from the API token "
                    + "(spring.jamjet.api-token), or uses \"default\" in dev mode. Use a token issued for "
                    + "the tenant you want and remove spring.jamjet.tenant-id.");
        }
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spring.jamjet.audit", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    public JamjetAuditAdvisor jamjetAuditAdvisor(
            JamjetRuntimeClient client, JamjetProperties properties) {
        log.info("Enabling JamJet audit trail advisor");
        return new JamjetAuditAdvisor(client, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spring.jamjet.audit", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    public JamjetAuditService jamjetAuditService(JamjetRuntimeClient client) {
        return new JamjetAuditService(client);
    }

    // ── Approval ──────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spring.jamjet.approval", name = "enabled",
                           havingValue = "true")
    public ApprovalWaitRegistry approvalWaitRegistry() {
        return new ApprovalWaitRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spring.jamjet.approval", name = "enabled",
                           havingValue = "true")
    public JamjetApprovalAdvisor jamjetApprovalAdvisor(
            JamjetRuntimeClient client, JamjetProperties properties,
            ApprovalWaitRegistry waitRegistry) {
        log.info("Enabling JamJet human-in-the-loop approval advisor");
        return new JamjetApprovalAdvisor(client, properties, waitRegistry);
    }

    /**
     * The approval endpoints. Registered when Spring Security is on the classpath
     * (decisions then require an authenticated principal), or when
     * {@code spring.jamjet.approval.controller.allow-unauthenticated=true}.
     */
    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "spring.jamjet.approval", name = "enabled",
                           havingValue = "true")
    @Conditional(ApprovalControllerCondition.class)
    public JamjetApprovalController jamjetApprovalController(
            JamjetRuntimeClient client, ApprovalWaitRegistry waitRegistry, JamjetProperties properties) {
        boolean open = properties.getApproval().getController().isAllowUnauthenticated();
        if (open) {
            log.warn("JamJet approval endpoints accept UNAUTHENTICATED decisions "
                    + "(spring.jamjet.approval.controller.allow-unauthenticated=true). Anyone who can reach "
                    + "/jamjet/approvals can approve this application's calls. Use this for local development only.");
        }
        log.info("Registering JamJet approval REST controller at /jamjet/approvals");
        return new JamjetApprovalController(client, waitRegistry, open);
    }

    /** Spring Security present, or unauthenticated decisions explicitly allowed. */
    static class ApprovalControllerCondition extends AnyNestedCondition {

        ApprovalControllerCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
        static class SpringSecurityPresent {
        }

        @ConditionalOnProperty(prefix = "spring.jamjet.approval.controller", name = "allow-unauthenticated",
                               havingValue = "true")
        static class UnauthenticatedAllowed {
        }
    }

    // ── Observability ────────────────────────────────────────────────────

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(prefix = "spring.jamjet.observability", name = "micrometer",
                           havingValue = "true", matchIfMissing = true)
    static class MicrometerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        public dev.jamjet.spring.observability.JamjetMicrometerBridge jamjetMicrometerBridge(
                io.micrometer.core.instrument.MeterRegistry registry, JamjetProperties properties) {
            LoggerFactory.getLogger(JamjetAutoConfiguration.class)
                    .info("Enabling JamJet Micrometer metrics bridge");
            return new dev.jamjet.spring.observability.JamjetMicrometerBridge(registry, properties);
        }
    }

    @Configuration
    @ConditionalOnClass(name = "io.opentelemetry.api.trace.Tracer")
    @ConditionalOnProperty(prefix = "spring.jamjet.observability", name = "opentelemetry",
                           havingValue = "true")
    static class OtelConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public dev.jamjet.spring.observability.JamjetOtelBridge jamjetOtelBridge(
                io.opentelemetry.api.trace.Tracer tracer, JamjetProperties properties) {
            LoggerFactory.getLogger(JamjetAutoConfiguration.class)
                    .info("Enabling JamJet OpenTelemetry tracing bridge");
            return new dev.jamjet.spring.observability.JamjetOtelBridge(tracer, properties);
        }
    }
}
