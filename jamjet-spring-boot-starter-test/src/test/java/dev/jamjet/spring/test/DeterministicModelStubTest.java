package dev.jamjet.spring.test;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicModelStubTest {

    @Test
    void matchesPromptPatterns() {
        var stub = DeterministicModelStub.builder()
                .onPromptContaining("weather", "It is sunny and 72F")
                .onPromptContaining("stock", "AAPL is at $150")
                .defaultResponse("I don't know")
                .build();

        var weatherResult = stub.call(new Prompt(new UserMessage("What's the weather today?")));
        assertThat(weatherResult.getResult().getOutput().getText()).isEqualTo("It is sunny and 72F");

        var stockResult = stub.call(new Prompt(new UserMessage("What is the stock price?")));
        assertThat(stockResult.getResult().getOutput().getText()).isEqualTo("AAPL is at $150");

        var unknownResult = stub.call(new Prompt(new UserMessage("Tell me a joke")));
        assertThat(unknownResult.getResult().getOutput().getText()).isEqualTo("I don't know");
    }

    @Test
    void matchesInOrder() {
        var stub = DeterministicModelStub.builder()
                .onPromptContaining("hello", "First match")
                .onPromptContaining("hello", "Second match")
                .build();

        var result = stub.call(new Prompt(new UserMessage("hello world")));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("First match");
    }

    @Test
    void recordsAllCalls() {
        var stub = DeterministicModelStub.builder()
                .defaultResponse("ok")
                .build();

        stub.call(new Prompt(new UserMessage("first")));
        stub.call(new Prompt(new UserMessage("second")));
        stub.call(new Prompt(new UserMessage("third")));

        assertThat(stub.getCallCount()).isEqualTo(3);
        assertThat(stub.getCalls()).hasSize(3);
        assertThat(stub.getCalls().get(0).getContents()).contains("first");
    }

    @Test
    void defaultResponseWhenNoPatternMatches() {
        var stub = DeterministicModelStub.builder()
                .onPromptContaining("specific", "matched")
                .defaultResponse("fallback")
                .build();

        var result = stub.call(new Prompt(new UserMessage("something else")));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("fallback");
    }

    @Test
    void defaultResponseIsEmptyWhenNotSet() {
        var stub = DeterministicModelStub.builder()
                .onPromptContaining("specific", "matched")
                .build();

        var result = stub.call(new Prompt(new UserMessage("something else")));
        assertThat(result.getResult().getOutput().getText()).isEmpty();
    }
}
