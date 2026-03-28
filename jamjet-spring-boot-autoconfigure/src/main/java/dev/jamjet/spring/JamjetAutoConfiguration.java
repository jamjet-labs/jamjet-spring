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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for JamJet durable execution with Spring AI.
 *
 * <p>Registers beans based on configuration:
 * <ul>
 *   <li>{@link JamjetRuntimeClient} — always (when durability enabled)</li>
 *   <li>{@link JamjetDurabilityAdvisor} — always (core durability)</li>
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
        return new JamjetRuntimeClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JamjetDurabilityAdvisor jamjetDurabilityAdvisor(
            JamjetRuntimeClient client, JamjetProperties properties) {
        log.info("Enabling JamJet durable execution advisor");
        return new JamjetDurabilityAdvisor(client, properties);
    }

    @Bean
    public ChatClientCustomizer jamjetChatClientCustomizer(
            JamjetDurabilityAdvisor durabilityAdvisor) {
        return builder -> builder.defaultAdvisors(durabilityAdvisor);
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

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "spring.jamjet.approval", name = "enabled",
                           havingValue = "true")
    public JamjetApprovalController jamjetApprovalController(
            JamjetRuntimeClient client, ApprovalWaitRegistry waitRegistry) {
        log.info("Registering JamJet approval REST controller at /jamjet/approvals");
        return new JamjetApprovalController(client, waitRegistry);
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
