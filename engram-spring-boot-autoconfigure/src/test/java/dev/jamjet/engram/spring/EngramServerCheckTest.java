package dev.jamjet.engram.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter speaks the Rust engram-server REST API. Pointed at anything
 * else, the application must not start and quietly lose memory: it fails at
 * startup and says which server it found and which one it needs.
 */
@ExtendWith(OutputCaptureExtension.class)
class EngramServerCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EngramAutoConfiguration.class));

    @Test
    void startsAgainstTheRustServer() throws Exception {
        try (var server = StubEngramServer.rust()) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(server.seen()).extracting(StubEngramServer.Seen::path).containsExactly("/health");
            });
        }
    }

    @Test
    void refusesToStartAgainstThePythonServer() throws Exception {
        try (var server = StubEngramServer.python()) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(EngramServerMismatchException.class)
                        .rootCause()
                        .hasMessageContaining(server.baseUrl())
                        .hasMessageContaining("Python Engram server")
                        .hasMessageContaining("jamjet-engram-server");
            });
        }
    }

    @Test
    void refusesToStartAgainstSomethingThatIsNotEngram() throws Exception {
        try (var server = StubEngramServer.empty()) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(EngramServerMismatchException.class)
                        .rootCause()
                        .hasMessageContaining("GET /health answered HTTP 404");
            });
        }
    }

    @Test
    void refusesToStartWhenHealthIsNotEngramJson() throws Exception {
        try (var server = StubEngramServer.empty().answerHtml("GET", "/health")) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(EngramServerMismatchException.class)
                        .rootCause()
                        .hasMessageContaining("not an Engram server");
            });
        }
    }

    @Test
    void refusesToStartWhenAnotherServiceAnswersHealth() throws Exception {
        // The JamJet runtime also serves GET /health with {"status":"ok"}.
        try (var server = StubEngramServer.empty()
                .answer("GET", "/health", 200, "{\"status\":\"ok\",\"service\":\"jamjet\"}")) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(EngramServerMismatchException.class)
                        .rootCause()
                        .hasMessageContaining("not an Engram server");
            });
        }
    }

    @Test
    void refusesToStartWhenTheServerRejectsTheToken() throws Exception {
        try (var server = StubEngramServer.empty()
                .answer("GET", "/health", 401, "{\"error\":\"invalid bearer token\"}")) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl(), "ENGRAM_AUTH_TOKEN=wrong")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(EngramServerMismatchException.class)
                                .rootCause()
                                .hasMessageContaining("HTTP 401")
                                .hasMessageContaining("ENGRAM_AUTH_TOKEN")
                                .hasMessageNotContaining("wrong");
                    });
        }
    }

    @Test
    void startsWithAWarningWhenTheServerIsNotReachable(CapturedOutput output) throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        runner.withPropertyValues("engram.base-url=http://127.0.0.1:" + closedPort).run(context -> {
            assertThat(context).hasNotFailed();
        });
        assertThat(output).contains("could not check the Engram server");
    }

    @Test
    void startsWithAWarningWhenTheServerErrors(CapturedOutput output) throws Exception {
        try (var server = StubEngramServer.empty().answer("GET", "/health", 503, "{}")) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl()).run(context -> {
                assertThat(context).hasNotFailed();
            });
        }
        assertThat(output).contains("could not check the Engram server").contains("HTTP 503");
    }

    @Test
    void theCheckCanBeTurnedOff() throws Exception {
        try (var server = StubEngramServer.python()) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl(), "engram.verify-on-startup=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(server.seen()).isEmpty();
                    });
        }
    }

    @Test
    void noCheckWhenTheApplicationBringsItsOwnClient() throws Exception {
        try (var server = StubEngramServer.python()) {
            runner.withPropertyValues("engram.base-url=" + server.baseUrl())
                    .withBean(dev.jamjet.engram.EngramClient.class, () -> new dev.jamjet.engram.EngramClient(
                            dev.jamjet.engram.EngramConfig.builder().baseUrl(server.baseUrl()).build()))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(server.seen()).isEmpty();
                    });
        }
    }
}
