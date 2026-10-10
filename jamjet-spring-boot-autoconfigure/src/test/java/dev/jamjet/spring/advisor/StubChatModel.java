package dev.jamjet.spring.advisor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A ChatModel that answers a fixed text, or throws, and counts its calls. */
public final class StubChatModel implements ChatModel {

    public final AtomicInteger calls = new AtomicInteger();
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
        if (failure != null) {
            throw failure;
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }
}
