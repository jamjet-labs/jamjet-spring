package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import dev.jamjet.spring.advisor.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A call marked as needing approval must never reach the model unless an
 * approval gate is actually on the ChatClient, whatever the configuration.
 */
class JamjetApprovalGateTest {

    /** Everything this jar registers as auto-configuration, as Spring Boot would load it. */
    private static AutoConfigurations everything() throws Exception {
        var classes = new ArrayList<Class<?>>();
        for (String name : ImportCandidates.load(AutoConfiguration.class, JamjetApprovalGateTest.class.getClassLoader())) {
            if (name.startsWith("dev.jamjet.spring.")) {
                classes.add(Class.forName(name));
            }
        }
        return AutoConfigurations.of(classes.toArray(Class<?>[]::new));
    }

    private static ChatClient customized(ApplicationContext context, StubChatModel model) {
        var builder = ChatClient.builder(model);
        context.getBeansOfType(ChatClientCustomizer.class).values().forEach(c -> c.customize(builder));
        return builder.build();
    }

    private static String sendWire(ChatClient chat) {
        return chat.prompt("send the wire")
                .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                .call().content();
    }

    @Test
    void approvalLeftAtItsDefaultOffRefusesAMarkedCall() throws Exception {
        new ApplicationContextRunner().withConfiguration(everything())
                .withPropertyValues("spring.jamjet.on-runtime-error=fail-open",
                        "spring.jamjet.runtime-url=http://127.0.0.1:9")
                .run(context -> {
                    var model = StubChatModel.answering("wire sent");
                    assertThatThrownBy(() -> sendWire(customized(context, model)))
                            .isInstanceOf(JamjetGovernanceException.class)
                            .hasMessageContaining("approval");
                    assertThat(model.calls).hasValue(0);
                });
    }

    @Test
    void durabilityOffWithApprovalOnStillRefusesAMarkedCall() throws Exception {
        new ApplicationContextRunner().withConfiguration(everything())
                .withPropertyValues("spring.jamjet.durability-enabled=false", "spring.jamjet.approval.enabled=true")
                .run(context -> {
                    var model = StubChatModel.answering("wire sent");
                    assertThatThrownBy(() -> sendWire(customized(context, model)))
                            .isInstanceOf(JamjetGovernanceException.class);
                    assertThat(model.calls).hasValue(0);
                });
    }

    @Test
    void anUnmarkedCallIsNotAffected() throws Exception {
        new ApplicationContextRunner().withConfiguration(everything())
                .withPropertyValues("spring.jamjet.durability-enabled=false")
                .run(context -> {
                    var model = StubChatModel.answering("hello");
                    assertThat(customized(context, model).prompt("hi").call().content()).isEqualTo("hello");
                });
    }

    @Test
    void aMarkedStreamingCallIsRefusedToo() throws Exception {
        new ApplicationContextRunner().withConfiguration(everything())
                .withPropertyValues("spring.jamjet.durability-enabled=false")
                .run(context -> {
                    var model = StubChatModel.answering("wire sent");
                    var chat = customized(context, model);
                    assertThatThrownBy(() -> chat.prompt("send the wire")
                            .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
                            .stream().content().collectList().block())
                            .isInstanceOf(JamjetGovernanceException.class);
                    assertThat(model.calls).hasValue(0);
                });
    }
}
