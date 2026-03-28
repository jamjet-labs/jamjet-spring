package dev.jamjet.spring.test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeterministicModelStub implements ChatModel {

    private final List<PatternResponse> patterns;
    private final String defaultResponse;
    private final List<Prompt> calls = Collections.synchronizedList(new ArrayList<>());

    private DeterministicModelStub(List<PatternResponse> patterns, String defaultResponse) {
        this.patterns = List.copyOf(patterns);
        this.defaultResponse = defaultResponse;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.add(prompt);
        String responseText = resolve(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    public List<Prompt> getCalls() {
        return List.copyOf(calls);
    }

    public int getCallCount() {
        return calls.size();
    }

    public void reset() {
        calls.clear();
    }

    public static Builder builder() {
        return new Builder();
    }

    private String resolve(Prompt prompt) {
        String content = prompt.getContents();
        for (PatternResponse pr : patterns) {
            if (content != null && content.contains(pr.pattern)) {
                return pr.response;
            }
        }
        return defaultResponse;
    }

    private record PatternResponse(String pattern, String response) {}

    public static class Builder {
        private final List<PatternResponse> patterns = new ArrayList<>();
        private String defaultResponse = "";

        public Builder onPromptContaining(String pattern, String response) {
            patterns.add(new PatternResponse(pattern, response));
            return this;
        }

        public Builder defaultResponse(String response) {
            this.defaultResponse = response;
            return this;
        }

        public DeterministicModelStub build() {
            return new DeterministicModelStub(patterns, defaultResponse);
        }
    }
}
