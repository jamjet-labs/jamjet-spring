package dev.jamjet.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.advisor.StubChatModel;
import dev.jamjet.spring.client.RecordingRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an application gets from the auto-configured ChatClientCustomizer, end to end. */
class JamjetChatClientWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingRuntime runtime;
    private ApplicationContextRunner runner;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class))
                .withPropertyValues("spring.jamjet.runtime-url=" + runtime.url());
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private static ChatClient customized(org.springframework.context.ApplicationContext context, StubChatModel model) {
        var builder = ChatClient.builder(model);
        context.getBean(ChatClientCustomizer.class).customize(builder);
        return builder.build();
    }

    private List<String> correlationKeys() throws Exception {
        var keys = new ArrayList<String>();
        for (var r : runtime.requests()) {
            if (r.path().endsWith("/external-event")) {
                keys.add(JSON.readTree(r.body()).get("correlation_key").asText());
            }
        }
        return keys;
    }

    @Test
    void theDefaultChatClientIsRecordedAndAudited() {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}")
                .on("POST", "/executions/exec_1/external-event", 200, "{\"accepted\":true}");

        runner.run(context -> {
            var answer = customized(context, StubChatModel.answering("hello")).prompt("hi").call().content();

            assertThat(answer).isEqualTo("hello");
            assertThat(correlationKeys())
                    .containsExactlyInAnyOrder("spring_ai.prompt", "spring_ai.response", "spring_ai.completed");
        });
    }

    @Test
    void theDefaultIsFailClosed() {
        runner.run(context -> {
            assertThat(context.getBean(JamjetProperties.class).getOnRuntimeError())
                    .isEqualTo(RuntimeErrorMode.FAIL_CLOSED);
            var model = StubChatModel.answering("hello");
            assertThatThrownBy(() -> customized(context, model).prompt("hi").call().content())
                    .isInstanceOf(JamjetGovernanceException.class);
            assertThat(model.calls).hasValue(0);
        });
    }

    @Test
    void failOpenIsAnExplicitOptIn() {
        runner.withPropertyValues("spring.jamjet.on-runtime-error=fail-open").run(context -> {
            assertThat(context.getBean(JamjetProperties.class).getOnRuntimeError())
                    .isEqualTo(RuntimeErrorMode.FAIL_OPEN);
            var model = StubChatModel.answering("hello");
            assertThat(customized(context, model).prompt("hi").call().content()).isEqualTo("hello");
        });
    }
}
