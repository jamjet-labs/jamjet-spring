package dev.jamjet.engram.spring;

import dev.jamjet.engram.spring.EngramProperties.ChatMemoryRepositoryMode;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when {@code engram.spring-ai.chat-memory-repository} is not {@code off}. */
class EngramChatMemoryRepositoryCondition extends SpringBootCondition {

    static final String PROPERTY = "engram.spring-ai.chat-memory-repository";

    static ChatMemoryRepositoryMode mode(org.springframework.core.env.Environment environment) {
        return Binder.get(environment).bind(PROPERTY, ChatMemoryRepositoryMode.class)
                .orElse(ChatMemoryRepositoryMode.OFF);
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ChatMemoryRepositoryMode mode = mode(context.getEnvironment());
        return mode == ChatMemoryRepositoryMode.OFF
                ? ConditionOutcome.noMatch(PROPERTY + " is off")
                : ConditionOutcome.match(PROPERTY + " is " + mode);
    }
}
