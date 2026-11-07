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
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

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
    void theStartupCheckDoesNotSendTheTokenToAServerItHasNotIdentified() {
        runner.withPropertyValues("ENGRAM_AUTH_TOKEN=s3cret").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(server.seen()).first().satisfies(first -> {
                assertThat(first.path()).isEqualTo("/health");
                assertThat(first.authorization()).isNull();
            });
        });
    }

    @Test
    void theStartupCheckSendsTheTokenWhenHealthDemandsIt() {
        server.requireToken("GET", "/health", "s3cret");
        runner.withPropertyValues("ENGRAM_AUTH_TOKEN=s3cret").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(server.seen()).extracting(StubEngramServer.Seen::authorization)
                    .startsWith(null, "Bearer s3cret");
        });
    }

    @Test
    void aTokenWithACharacterOutsideVisibleAsciiFailsWithoutPrintingIt(CapturedOutput output) {
        // A zero-width space pasted with the token: the JDK refuses it in a header
        // and its error message prints the whole header value.
        withRawEnvironment("ENGRAM_AUTH_TOKEN", "tok-SECRET\u200B").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("ENGRAM_AUTH_TOKEN")
                    .hasMessageNotContaining("tok-SECRET");
        });
        assertThat(output).doesNotContain("tok-SECRET");
    }

    @Test
    void aTokenWithASpaceInsideIsRefused() {
        withRawEnvironment("ENGRAM_AUTH_TOKEN", "tok SECRET").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void warnsForAHostNameWithAnUnderscore(CapturedOutput output) {
        plainHttpRunner("http://engram_host:9090").run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("plain HTTP");
    }

    @Test
    void warnsForADnsNameThatStartsWith127(CapturedOutput output) {
        plainHttpRunner("http://127.example.invalid:9090").run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("plain HTTP");
    }

    @Test
    void noPlainHttpWarningForLocalhost(CapturedOutput output) {
        plainHttpRunner("http://localhost:9090").run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain("plain HTTP");
    }

    private static ApplicationContextRunner plainHttpRunner(String baseUrl) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EngramAutoConfiguration.class))
                .withPropertyValues("engram.base-url=" + baseUrl,
                        "engram.verify-on-startup=false", "ENGRAM_AUTH_TOKEN=s3cret");
    }

    /** {@code withPropertyValues} trims values, so raw ones go in as a property source. */
    private ApplicationContextRunner withRawEnvironment(String name, String value) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("raw", Map.of(name, value))));
    }

    @Test
    void aTokenReadFromAFileWithATrailingNewlineIsTrimmed() {
        assertThat(authorizationSentBy(withRawEnvironment("ENGRAM_AUTH_TOKEN", "tok-SECRET-123\n")))
                .isEqualTo("Bearer tok-SECRET-123");
    }

    @Test
    void aTokenWithAControlCharacterFailsWithoutPrintingIt(CapturedOutput output) {
        withRawEnvironment("ENGRAM_AUTH_TOKEN", "tok-SECRET\r\nX-Injected: 1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("ENGRAM_AUTH_TOKEN")
                    .hasMessageNotContaining("tok-SECRET");
        });
        assertThat(output).doesNotContain("tok-SECRET");
    }

    @Test
    void warnsWhenTheTokenGoesOverPlainHttpToAnotherHost(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EngramAutoConfiguration.class))
                .withPropertyValues("engram.base-url=http://engram.example.invalid:9090",
                        "engram.verify-on-startup=false", "ENGRAM_AUTH_TOKEN=s3cret")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("plain HTTP").doesNotContain("s3cret");
    }

    @Test
    void noPlainHttpWarningForLoopback(CapturedOutput output) {
        runner.withPropertyValues("ENGRAM_AUTH_TOKEN=s3cret").run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain("plain HTTP");
    }

    @Test
    void noTokenSendsNoAuthorizationHeader() {
        assumeTrue(System.getenv("ENGRAM_AUTH_TOKEN") == null && System.getenv("ENGRAM_TOKEN") == null,
                "a real ENGRAM_AUTH_TOKEN or ENGRAM_TOKEN is set in this environment");
        assertThat(authorizationSentBy(runner)).isNull();
    }
}
