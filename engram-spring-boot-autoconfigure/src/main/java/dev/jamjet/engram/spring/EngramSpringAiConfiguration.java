package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI integration for Engram.
 *
 * <p>Activates only when Spring AI's {@code ChatClient} is on the classpath.
 * Registers an {@link EngramContextAdvisor} bean and a
 * {@link ChatClientCustomizer} that wires the advisor into every ChatClient
 * the user constructs through the default builder.
 *
 * <p>Users who want manual control can set
 * {@code engram.spring-ai.auto-wire=false} and inject the advisor themselves.
 */
@AutoConfiguration(after = EngramAutoConfiguration.class)
@ConditionalOnClass({ChatClient.class, EngramClient.class})
@EnableConfigurationProperties(EngramProperties.class)
@ConditionalOnProperty(prefix = "engram", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class EngramSpringAiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngramSpringAiConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public EngramContextAdvisor engramContextAdvisor(
            EngramClient client, EngramProperties properties) {
        log.info("Registering Engram context advisor for Spring AI ChatClient");
        return new EngramContextAdvisor(client, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "engram.spring-ai", name = "auto-wire",
                           havingValue = "true", matchIfMissing = true)
    public ChatClientCustomizer engramChatClientCustomizer(
            EngramContextAdvisor advisor) {
        return builder -> builder.defaultAdvisors(advisor);
    }

    @Bean
    @ConditionalOnClass(ChatMemoryRepository.class)
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    @ConditionalOnProperty(prefix = "engram.spring-ai", name = "chat-memory-repository",
                           havingValue = "true", matchIfMissing = true)
    public EngramChatMemoryRepository engramChatMemoryRepository(
            EngramClient client, EngramProperties properties) {
        log.info("Registering Engram ChatMemoryRepository for Spring AI");
        return new EngramChatMemoryRepository(client, properties);
    }
}
