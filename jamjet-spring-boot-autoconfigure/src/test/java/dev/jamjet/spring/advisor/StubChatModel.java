package dev.jamjet.spring.advisor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A ChatModel that answers a fixed text, or throws, and counts its calls. */
public final class StubChatModel implements ChatModel {

    public final AtomicInteger calls = new AtomicInteger();
    public volatile Prompt lastPrompt;
    private final String answer;
    private final RuntimeException failure;

    private StubChatModel(String answer, RuntimeException failure) {
        this.answer = answer;
        this.failure = failure;
    }

    public static StubChatModel answering(String answer) {
        return new StubChatModel(answer, null);
    }

    public static StubChatModel failingWith(RuntimeException failure) {
        return new StubChatModel(null, failure);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        lastPrompt = prompt;
        if (failure != null) {
            throw failure;
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    /** Streams the answer word by word (a trailing space kept on each chunk but the last). */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            if (failure != null) {
                return Flux.error(failure);
            }
            String[] words = answer.split(" ");
            var chunks = new java.util.ArrayList<ChatResponse>();
            for (int i = 0; i < words.length; i++) {
                String text = i < words.length - 1 ? words[i] + " " : words[i];
                var metadata = i < words.length - 1
                        ? org.springframework.ai.chat.metadata.ChatGenerationMetadata.NULL
                        : org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("STOP").build();
                chunks.add(new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata))));
            }
            return Flux.fromIterable(chunks);
        });
    }
}
