package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetApprovalGuard;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Always-on approval gate. Deliberately has no property condition: a call marked
 * as needing approval is refused whenever no approval advisor would gate it,
 * including when the rest of the JamJet auto-configuration is switched off.
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
public class JamjetApprovalGuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JamjetApprovalGuard jamjetApprovalGuard() {
        return new JamjetApprovalGuard();
    }

    @Bean
    public ChatClientCustomizer jamjetApprovalGuardCustomizer(JamjetApprovalGuard guard) {
        return builder -> builder.defaultAdvisors(guard);
    }
}
