package dev.jamjet.engram.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * engram.spring-ai.chat-memory-repository: off (default), save-only, or on.
 * 0.2.0 registered the repository by default; its saves fed Engram (and fact
 * extraction), its reads always came back empty on jamjet-engram-server 0.5.0.
 * save-only keeps the saves and says once that reads come back empty.
 */
@ExtendWith(OutputCaptureExtension.class)
class EngramChatMemoryModeTest {

    private StubEngramServer server;
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() throws Exception {
        server = StubEngramServer.rust()
                .answer("POST", "/v1/memory/messages", 201, "{\"success\":true,\"message_ids\":[\"m1\"]}");
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EngramAutoConfiguration.class, EngramSpringAiConfiguration.class))
                .withPropertyValues("engram.base-url=" + server.baseUrl(), "engram.default-user-id=alice");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void saveOnlySavesAndReadsComeBackEmptyWithOneWarning(CapturedOutput output) {
        runner.withPropertyValues("engram.spring-ai.chat-memory-repository=save-only").run(context -> {
            assertThat(context).hasNotFailed();
            ChatMemoryRepository repository = context.getBean(ChatMemoryRepository.class);

            // What MessageWindowChatMemory.add does: read, then save.
            MessageWindowChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
            memory.add("conv-1", new UserMessage("I like tea"));
            memory.add("conv-1", new UserMessage("and biscuits"));

            assertThat(server.seen()).filteredOn(s -> s.method().equals("POST")).hasSize(2);
            assertThat(server.seen()).noneMatch(s -> s.path().startsWith("/v1/memory/messages/"));
            repository.deleteByConversationId("conv-1");
            assertThat(server.seen()).noneMatch(s -> s.method().equals("DELETE"));
        });
        assertThat(output).containsOnlyOnce("Engram chat memory is save-only");
    }

    @Test
    void trueAndOnMeanOn() {
        for (String value : List.of("true", "on")) {
            runner.withPropertyValues("engram.spring-ai.chat-memory-repository=" + value)
                    .run(context -> assertThat(context).hasSingleBean(EngramChatMemoryRepository.class));
        }
    }

    @Test
    void falseAndOffMeanOff() {
        for (String value : List.of("false", "off")) {
            runner.withPropertyValues("engram.spring-ai.chat-memory-repository=" + value)
                    .run(context -> assertThat(context).doesNotHaveBean(EngramChatMemoryRepository.class));
        }
    }

    @Test
    void warnsAtStartupWhenAChatMemoryIsNotBackedByEngram(CapturedOutput output) {
        runner.withBean(ChatMemory.class, () -> MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository()).build())
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("engram.spring-ai.chat-memory-repository");
    }

    @Test
    void noStartupWarningInSaveOnlyMode(CapturedOutput output) {
        runner.withPropertyValues("engram.spring-ai.chat-memory-repository=save-only")
                .withBean(ChatMemory.class, () -> MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository()).build())
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain("conversations are not saved to Engram");
    }
}
