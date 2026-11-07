package dev.jamjet.engram.spring;

import dev.jamjet.engram.EngramClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Engram names its server token {@code ENGRAM_AUTH_TOKEN}. The client built
 * by the starter must read the same variable and send it as a bearer token on
 * every request, or a server started with a token answers 401.
 */
@ExtendWith(OutputCaptureExtension.class)
class EngramTokenTest {

    private StubEngramServer server;
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() throws Exception {
        server = StubEngramServer.rust().answer("GET", "/v1/memory/stats", 200, "{}");
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EngramAutoConfiguration.class))
                .withPropertyValues("engram.base-url=" + server.baseUrl());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String authorizationSentBy(ApplicationContextRunner configured) {
        String[] header = new String[1];
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(EngramClient.class).stats();
            var seen = server.seen();
            var last = seen.get(seen.size() - 1);
            assertThat(last.path()).isEqualTo("/v1/memory/stats");
            header[0] = last.authorization();
        });
        return header[0];
    }

    @Test
    void sendsEngramAuthTokenAsBearer() {
        assertThat(authorizationSentBy(runner.withPropertyValues("ENGRAM_AUTH_TOKEN=s3cret")))
                .isEqualTo("Bearer s3cret");
    }

    @Test
    void apiTokenPropertyWinsOverTheEnvironment() {
        assertThat(authorizationSentBy(runner.withPropertyValues(
                "engram.api-token=from-property", "ENGRAM_AUTH_TOKEN=from-env")))
                .isEqualTo("Bearer from-property");
    }

    @Test
    void blankApiTokenPropertyFallsBackToEngramAuthToken() {
        // application.yml often has api-token: ${SOME_VAR:} which binds to ""
        assertThat(authorizationSentBy(runner.withPropertyValues(
                "engram.api-token=", "ENGRAM_AUTH_TOKEN=s3cret")))
                .isEqualTo("Bearer s3cret");
    }

    @Test
    void engramAuthTokenWinsOverTheDeprecatedEngramToken() {
        assertThat(authorizationSentBy(runner.withPropertyValues(
                "ENGRAM_AUTH_TOKEN=current", "ENGRAM_TOKEN=old")))
                .isEqualTo("Bearer current");
    }

    @Test
    void deprecatedEngramTokenStillWorksAndWarns(CapturedOutput output) {
        assertThat(authorizationSentBy(runner.withPropertyValues("ENGRAM_TOKEN=legacy-7f3a")))
                .isEqualTo("Bearer legacy-7f3a");
        assertThat(output).contains("ENGRAM_TOKEN is deprecated").contains("ENGRAM_AUTH_TOKEN");
        assertThat(output).doesNotContain("legacy-7f3a");
    }

    @Test
    void theStartupCheckSendsTheTokenToo() {
        runner.withPropertyValues("ENGRAM_AUTH_TOKEN=s3cret").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(server.seen()).first().satisfies(first -> {
                assertThat(first.path()).isEqualTo("/health");
                assertThat(first.authorization()).isEqualTo("Bearer s3cret");
            });
        });
    }

    @Test
    void noTokenSendsNoAuthorizationHeader() {
        assumeTrue(System.getenv("ENGRAM_AUTH_TOKEN") == null && System.getenv("ENGRAM_TOKEN") == null,
                "a real ENGRAM_AUTH_TOKEN or ENGRAM_TOKEN is set in this environment");
        assertThat(authorizationSentBy(runner)).isNull();
    }
}
