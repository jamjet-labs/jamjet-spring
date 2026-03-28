package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for JamJet durable execution with Spring AI.
 *
 * <p>Activates when:
 * <ul>
 *   <li>Spring AI's {@code ChatClient} is on the classpath</li>
 *   <li>{@code spring.jamjet.durability-enabled} is true (default)</li>
 * </ul>
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link JamjetRuntimeClient} — HTTP client for the JamJet runtime</li>
 *   <li>{@link JamjetDurabilityAdvisor} — BaseAdvisor wrapping calls with durable execution</li>
 *   <li>{@link ChatClientCustomizer} — auto-injects the advisor into every ChatClient</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(JamjetProperties.class)
@ConditionalOnProperty(prefix = "spring.jamjet", name = "durability-enabled",
                       havingValue = "true", matchIfMissing = true)
public class JamjetAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JamjetAutoConfiguration.class);

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
}
