package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import dev.jamjet.engram.EngramConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A route the server does not serve is a deployment mistake, not a passing
 * outage. The advisor and the chat memory repository used to log it and carry
 * on with no memory; now they throw, and say which route was missing. Real
 * outages (5xx, connection refused) keep the old degrade-and-log behaviour.
 *
 * <p>These tests drive the real {@link EngramClient} from jamjet-sdk against a
 * local server, so they also pin how the starter reads the SDK's errors.
 */
class EngramRouteNotServedTest {

    private StubEngramServer server;
    private EngramProperties properties;
    private EngramClient client;

    @BeforeEach
    void start() throws Exception {
        server = StubEngramServer.rust();
        properties = new EngramProperties();
        properties.setBaseUrl(server.baseUrl());
        properties.setDefaultUserId("alice");
        client = new EngramClient(EngramConfig.builder().baseUrl(server.baseUrl()).apiToken("t").build());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static ChatClientRequest request(String text) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(text)))
                .context(Map.of(EngramContextAdvisor.USER_ID_KEY, "alice"))
                .build();
    }

    // ── EngramContextAdvisor ─────────────────────────────────────────────────

    @Test
    void advisorThrowsWhenContextRouteIsNotServed() {
        // The Python server serves GET /v1/memory/context, so POST gets 405.
        server.answer("POST", "/v1/memory/context", 405, "{\"detail\":\"Method Not Allowed\"}");
        var advisor = new EngramContextAdvisor(client, properties);

        assertThatThrownBy(() -> advisor.before(request("what do I like?"), null))
                .isInstanceOf(EngramServerMismatchException.class)
                .hasMessageContaining("HTTP 405")
                .hasMessageContaining("POST /v1/memory/context")
                .hasMessageContaining(server.baseUrl());
    }

    @Test
    void advisorThrowsWhenContextRouteIs404() {
        var advisor = new EngramContextAdvisor(client, properties);

        assertThatThrownBy(() -> advisor.before(request("what do I like?"), null))
                .isInstanceOf(EngramServerMismatchException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void advisorStillDegradesOnAServerError() {
        server.answer("POST", "/v1/memory/context", 500, "{\"error\":\"embedding error\"}");
        var advisor = new EngramContextAdvisor(client, properties);
        var original = request("what do I like?");

        assertThat(advisor.before(original, null)).isSameAs(original);
    }

    @Test
    void advisorInjectsContextFromTheRustServer() {
        server.answer("POST", "/v1/memory/context", 200,
                "{\"text\":\"<memory>likes tea</memory>\",\"facts_included\":1,\"token_count\":5}");
        var advisor = new EngramContextAdvisor(client, properties);

        var enriched = advisor.before(request("what do I like?"), null);

        assertThat(enriched.prompt().getInstructions().get(0).getText()).isEqualTo("<memory>likes tea</memory>");
    }

    private static EngramClient clientForAClosedPort() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return new EngramClient(EngramConfig.builder().baseUrl("http://127.0.0.1:" + port).build());
    }

    @Test
    void aConnectionFailureDoesNotLeaveTheThreadInterrupted() throws Exception {
        // jamjet-sdk 0.5.0 interrupts the calling thread on any IOException, which
        // made the model call that follows on the same thread fail.
        var advisor = new EngramContextAdvisor(clientForAClosedPort(), properties);
        var original = request("what do I like?");
        try {
            assertThat(advisor.before(original, null)).isSameAs(original);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aRealInterruptIsKept() throws Exception {
        var advisor = new EngramContextAdvisor(clientForAClosedPort(), properties);
        Thread.currentThread().interrupt();
        try {
            advisor.before(request("what do I like?"), null);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ── EngramChatMemoryRepository ───────────────────────────────────────────

    @Test
    void repositoryConnectionFailureDoesNotLeaveTheThreadInterrupted() throws Exception {
        var repository = new EngramChatMemoryRepository(clientForAClosedPort(), properties);
        try {
            assertThat(repository.findByConversationId("conv-1")).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void repositoryThrowsInsteadOfReturningNoHistory() {
        // jamjet-engram-server 0.5.0 answers 404 here for every conversation id.
        var repository = new EngramChatMemoryRepository(client, properties);

        assertThatThrownBy(() -> repository.findByConversationId("conv-1"))
                .isInstanceOf(EngramServerMismatchException.class)
                .hasMessageContaining("GET /v1/memory/messages/{conversation_id}")
                .hasMessageContaining("engram.spring-ai.chat-memory-repository=false");
    }

    @Test
    void repositoryThrowsWhenDeleteIsNotServed() {
        var repository = new EngramChatMemoryRepository(client, properties);

        assertThatThrownBy(() -> repository.deleteByConversationId("conv-1"))
                .isInstanceOf(EngramServerMismatchException.class)
                .hasMessageContaining("DELETE /v1/memory/messages/{conversation_id}");
    }

    @Test
    void repositoryThrowsWhenListingIsNotServed() {
        var repository = new EngramChatMemoryRepository(client, properties);

        assertThatThrownBy(repository::findConversationIds)
                .isInstanceOf(EngramServerMismatchException.class)
                .hasMessageContaining("GET /v1/memory/messages");
    }

    @Test
    void repositorySaveNamesTheMissingRoute() {
        var repository = new EngramChatMemoryRepository(client, properties);

        assertThatThrownBy(() -> repository.saveAll("conv-1", List.of(new UserMessage("hi"))))
                .isInstanceOf(EngramServerMismatchException.class)
                .hasMessageContaining("POST /v1/memory/messages");
    }

    @Test
    void repositoryStillDegradesOnAServerError() {
        server.answer("GET", "/v1/memory/messages/conv-1", 500, "{\"error\":\"db locked\"}");
        var repository = new EngramChatMemoryRepository(client, properties);

        assertThat(repository.findByConversationId("conv-1")).isEmpty();
    }

    @Test
    void repositoryReadsHistoryFromAServerThatServesTheRoute() {
        server.answer("GET", "/v1/memory/messages/conv-1", 200,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"total\":1}");
        var repository = new EngramChatMemoryRepository(client, properties);

        assertThat(repository.findByConversationId("conv-1")).extracting(m -> m.getText()).containsExactly("hi");
    }
}
