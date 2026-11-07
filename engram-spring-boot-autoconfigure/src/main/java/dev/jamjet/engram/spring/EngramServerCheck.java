package dev.jamjet.engram.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Startup check that {@code engram.base-url} points at the Rust engram-server.
 *
 * <p>It sends {@code GET /health} once and expects
 * {@code {"status":"ok","service":"engram"}}. A server that answers something
 * else (the Python Engram server, another service, a 401) fails startup with
 * an {@link EngramServerMismatchException} that says what was found. A server
 * that cannot be reached or answers 5xx only logs a warning, so an Engram
 * outage never stops the application from starting.
 *
 * <p>Turn it off with {@code engram.verify-on-startup=false}.
 */
final class EngramServerCheck {

    private static final Logger log = LoggerFactory.getLogger(EngramServerCheck.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROBE_SECONDS = 5;

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    EngramServerCheck(String baseUrl, String token, int timeoutSeconds) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, MAX_PROBE_SECONDS)));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    void verify() {
        HttpResponse<String> health;
        try {
            health = get("/health");
        } catch (IOException e) {
            warnUnchecked("it could not be reached (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnUnchecked("the check was interrupted");
            return;
        }

        int status = health.statusCode();
        if (status == 200) {
            if (isEngramHealth(health.body())) {
                log.debug("Engram server at {} is the Rust engram-server", baseUrl);
                return;
            }
            throw mismatch("GET /health answered HTTP 200 with " + describe(health)
                    + ", which is not an Engram server.");
        }
        if (status == 401 || status == 403) {
            throw mismatch("GET /health answered HTTP " + status + ": the server refused "
                    + (token == null ? "a request without a token" : "the configured token")
                    + ". Set ENGRAM_AUTH_TOKEN (or engram.api-token) to the token the server was started with.");
        }
        if (status == 404 || status == 405) {
            throw mismatch("GET /health answered HTTP " + status + "." + whatAnswersHealthz());
        }
        warnUnchecked("GET /health answered HTTP " + status);
    }

    private String whatAnswersHealthz() {
        try {
            HttpResponse<String> healthz = get("/healthz");
            if (healthz.statusCode() == 200) {
                return " GET /healthz answered HTTP 200, so this looks like the Python Engram server.";
            }
        } catch (IOException e) {
            // fall through: the /health answer already decides
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return " This is not an Engram REST server, or engram.base-url has the wrong host, port or path.";
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isEngramHealth(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject()
                    && "ok".equals(node.path("status").asText(null))
                    && "engram".equals(node.path("service").asText(null));
        } catch (IOException e) {
            return false;
        }
    }

    private static String describe(HttpResponse<String> response) {
        String type = response.headers().firstValue("Content-Type").orElse("no content type");
        String body = response.body() == null ? "" : response.body().strip();
        if (body.length() > 80) {
            body = body.substring(0, 80) + "...";
        }
        return type + " body \"" + body + "\"";
    }

    private EngramServerMismatchException mismatch(String finding) {
        return new EngramServerMismatchException("Engram server at " + baseUrl + ": " + finding + " "
                + EngramRoutes.SUPPORTED_SERVER
                + " Fix engram.base-url, or set engram.verify-on-startup=false to skip this check.");
    }

    private void warnUnchecked(String reason) {
        log.warn("Engram: could not check the Engram server at {} at startup: {}. "
                + "Memory is unavailable until it answers.", baseUrl, reason);
    }
}
