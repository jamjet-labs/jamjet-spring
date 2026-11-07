package dev.jamjet.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.spring.advisor.StubChatModel;
import dev.jamjet.spring.client.RecordingRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an application gets from the auto-configured ChatClientBuilderCustomizer, end to end. */
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
        context.getBean(ChatClientBuilderCustomizer.class).customize(builder);
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

    /** Asks for the lookup tool on the first round, answers on the second. */
    static final class ToolCallingModel implements org.springframework.ai.chat.model.ChatModel {
        final List<org.springframework.ai.chat.prompt.Prompt> prompts =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            prompts.add(prompt);
            var message = prompts.size() == 1
                    ? org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                            .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "t1", "function", "lookup", "\"acct\"")))
                            .build()
                    : new org.springframework.ai.chat.messages.AssistantMessage("done");
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(message)));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
        }
    }

    static org.springframework.ai.tool.ToolCallback lookupTool(java.util.concurrent.atomic.AtomicInteger runs) {
        return org.springframework.ai.tool.function.FunctionToolCallback
                .builder("lookup", (java.util.function.Function<String, String>) s -> {
                    runs.incrementAndGet();
                    return "42";
                })
                .description("look up an account").inputType(String.class).build();
    }

    /**
     * Spring AI 2.0 runs the tool loop in a ToolCallingAdvisor that calls the rest
     * of the advisor chain once per model round. The call is still one execution:
     * the durability advisor sits outside the loop, and the audit advisor records
     * every round on that execution.
     */
    @Test
    void aCallThatUsesToolsIsOneExecutionWithEveryRoundAudited() {
        runtime.on("POST", "/workflows", 201, "{\"workflow_id\":\"spring-ai-chat\",\"version\":\"1.0.0\"}")
                .on("POST", "/executions", 201, "{\"execution_id\":\"exec_1\"}")
                .on("POST", "/executions/exec_1/external-event", 200, "{\"accepted\":true}");
        var model = new ToolCallingModel();
        var toolRuns = new java.util.concurrent.atomic.AtomicInteger();

        runner.run(context -> {
            var builder = ChatClient.builder(model);
            context.getBean(ChatClientBuilderCustomizer.class).customize(builder);
            var answer = builder.build().prompt("hi").toolCallbacks(lookupTool(toolRuns)).call().content();

            assertThat(answer).isEqualTo("done");
            assertThat(model.prompts).hasSize(2);
            assertThat(toolRuns).hasValue(1);
            assertThat(runtime.requests()).filteredOn(r -> r.method().equals("POST") && r.path().equals("/executions"))
                    .as("one execution for the whole call").hasSize(1);
            assertThat(correlationKeys()).containsExactly("spring_ai.prompt", "spring_ai.response",
                    "spring_ai.prompt", "spring_ai.response", "spring_ai.completed");
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
