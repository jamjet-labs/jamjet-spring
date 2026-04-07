package dev.jamjet.engram.spring;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EngramSpringAiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EngramAutoConfiguration.class,
                    EngramSpringAiConfiguration.class));

    @Test
    void contextAdvisorIsRegisteredWhenSpringAiIsOnClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EngramContextAdvisor.class);
        });
    }

    @Test
    void chatClientCustomizerIsRegisteredByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("engramChatClientCustomizer");
            assertThat(context).getBean("engramChatClientCustomizer", ChatClientCustomizer.class).isNotNull();
        });
    }

    @Test
    void chatClientCustomizerSkippedWhenAutoWireDisabled() {
        contextRunner
                .withPropertyValues("engram.spring-ai.auto-wire=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(EngramContextAdvisor.class);
                    assertThat(context).doesNotHaveBean("engramChatClientCustomizer");
                });
    }

    @Test
    void noAdvisorWhenEngramDisabled() {
        contextRunner
                .withPropertyValues("engram.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EngramContextAdvisor.class);
                });
    }
}
