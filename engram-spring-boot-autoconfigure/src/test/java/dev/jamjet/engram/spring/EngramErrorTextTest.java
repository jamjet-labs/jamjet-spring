package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import dev.jamjet.engram.EngramConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An application can build its own EngramClient with any token. If the JDK
 * refuses that token in a header, its error message holds the whole header,
 * so the starter must never log it or show it in health details.
 */
@ExtendWith(OutputCaptureExtension.class)
class EngramErrorTextTest {

    private static final String SECRET = "tok-SECRET-\u200B";

    private StubEngramServer server;
    private EngramClient client;
    private EngramProperties properties;

    @BeforeEach
    void start() throws Exception {
        server = StubEngramServer.rust();
        client = new EngramClient(EngramConfig.builder().baseUrl(server.baseUrl()).apiToken(SECRET).build());
        properties = new EngramProperties();
        properties.setBaseUrl(server.baseUrl());
        properties.setDefaultUserId("alice");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void healthDetailsDoNotShowTheToken() {
        Health health = new EngramHealthIndicator(client).health();
        assertThat(health.getDetails().toString()).doesNotContain("tok-SECRET");
    }

    @Test
    void theAdvisorDoesNotLogTheToken(CapturedOutput output) {
        var request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("hi")))
                .context(Map.of(EngramContextAdvisor.USER_ID_KEY, "alice"))
                .build();
        new EngramContextAdvisor(client, properties).before(request, null);
        assertThat(output).doesNotContain("tok-SECRET");
    }

    @Test
    void theRepositoryDoesNotLogOrThrowTheToken(CapturedOutput output) {
        var repository = new EngramChatMemoryRepository(client, properties);
        repository.findConversationIds();
        assertThatThrownBy(() -> repository.saveAll("c", List.of(new UserMessage("hi"))))
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain("tok-SECRET");
                    }
                });
        assertThat(output).doesNotContain("tok-SECRET");
    }
}
