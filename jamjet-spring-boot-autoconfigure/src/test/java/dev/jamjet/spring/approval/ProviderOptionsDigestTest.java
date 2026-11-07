package dev.jamjet.spring.approval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Options beyond the portable ones are part of what is approved. */
class ProviderOptionsDigestTest {

    /** Stands in for a provider's options class (OpenAI toolChoice, seed and so on). */
    public static class ProviderOptions implements ChatOptions {
        private final String model;
        private final String toolChoice;
        private final Integer seed;

        ProviderOptions(String model, String toolChoice, Integer seed) {
            this.model = model;
            this.toolChoice = toolChoice;
            this.seed = seed;
        }

        public String getToolChoice() {
            return toolChoice;
        }

        public Integer getSeed() {
            return seed;
        }

        @Override
        public String getModel() {
            return model;
        }

        @Override
        public Double getFrequencyPenalty() {
            return null;
        }

        @Override
        public Integer getMaxTokens() {
            return null;
        }

        @Override
        public Double getPresencePenalty() {
            return null;
        }

        @Override
        public java.util.List<String> getStopSequences() {
            return null;
        }

        @Override
        public Double getTemperature() {
            return null;
        }

        @Override
        public Integer getTopK() {
            return null;
        }

        @Override
        public Double getTopP() {
            return null;
        }

        @Override
        public ChatOptions.Builder<?> mutate() {
            return ChatOptions.builder().model(model);
        }
    }

    private static String digest(ChatOptions options) {
        return ApprovalContent.digest(new Prompt(new UserMessage("pay"), options));
    }

    private static ProviderOptions provider(String toolChoice, Integer seed) {
        return new ProviderOptions("m", toolChoice, seed);
    }

    @Test
    void providerSpecificOptionsAreInTheDigest() {
        assertThat(digest(provider("auto", 1))).isEqualTo(digest(provider("auto", 1)));
        assertThat(digest(provider("auto", 1))).isNotEqualTo(digest(provider("sendWire", 1)));
        assertThat(digest(provider("auto", 1))).isNotEqualTo(digest(provider("auto", 2)));
    }

    @Test
    void penaltiesAreInTheDigest() {
        var low = ChatOptions.builder().model("m").frequencyPenalty(0.0).presencePenalty(0.0).build();
        var high = ChatOptions.builder().model("m").frequencyPenalty(1.0).presencePenalty(0.0).build();
        var presence = ChatOptions.builder().model("m").frequencyPenalty(0.0).presencePenalty(1.0).build();

        assertThat(digest(low)).isNotEqualTo(digest(high)).isNotEqualTo(digest(presence));
    }

    @Test
    void theToolContextIsInTheDigest() {
        var wire = ApprovalContentTest.tool("sendWire", "send money", "{}", false);
        var a = ToolCallingChatOptions.builder().toolCallbacks(wire).toolContext(Map.of("account", "42")).build();
        var b = ToolCallingChatOptions.builder().toolCallbacks(wire).toolContext(Map.of("account", "666")).build();

        assertThat(digest(a)).isNotEqualTo(digest(b));
    }

    @Test
    void toolCallbacksWithLambdasStillDigest() {
        var cb = FunctionToolCallback.builder("sendWire", (Function<String, String>) s -> s)
                .description("send money").inputType(String.class).build();
        var options = ToolCallingChatOptions.builder().toolCallbacks(cb).build();

        assertThat(digest(options)).hasSize(64);
    }

    /** Jackson cannot serialize this (no properties), so its string form is digested. */
    static final class Opaque {
        private final String id;

        Opaque(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "opaque:" + id;
        }
    }

    @Test
    void aToolContextValueJacksonCannotSerializeIsStillDigested() {
        var a = ToolCallingChatOptions.builder().toolContext(Map.of("handle", new Opaque("a"))).build();
        var b = ToolCallingChatOptions.builder().toolContext(Map.of("handle", new Opaque("b"))).build();

        assertThat(digest(a)).isNotEqualTo(digest(b));
    }

    @Test
    void theSameToolsGiveTheSameDigestEveryTime() {
        Function<String, String> fn = s -> s;
        var one = ToolCallingChatOptions.builder().toolCallbacks(FunctionToolCallback.builder("sendWire", fn)
                .description("send money").inputType(String.class).build()).build();
        var two = ToolCallingChatOptions.builder().toolCallbacks(FunctionToolCallback.builder("sendWire", fn)
                .description("send money").inputType(String.class).build()).build();

        assertThat(digest(one)).as("tool callbacks hold code; only their definitions are digested")
                .isEqualTo(digest(two));
    }

    /** A tool callback carrying runtime state that has no stable representation. */
    static final class StatefulCallback implements org.springframework.ai.tool.ToolCallback {
        private final Object handle = new Object();

        public Object getHandle() {
            return handle;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name("sendWire").description("send money").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            return toolInput;
        }
    }

    @Test
    void onlyAToolsDefinitionIsDigestedNeverItsRuntimeState() {
        var one = ToolCallingChatOptions.builder().toolCallbacks(new StatefulCallback()).build();
        var two = ToolCallingChatOptions.builder().toolCallbacks(new StatefulCallback()).build();

        assertThat(digest(one)).isEqualTo(digest(two));
    }
}
