package dev.jamjet.engram.spring;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local HTTP server that answers like an Engram server would, so tests can
 * drive the real {@code EngramClient} from jamjet-sdk and see what it sends.
 */
final class StubEngramServer implements AutoCloseable {

    record Seen(String method, String path, String authorization) {}

    record Answer(int status, String contentType, String body) {}

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final Map<String, String> requiredTokens = new ConcurrentHashMap<>();

    private StubEngramServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            seen.add(new Seen(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.getRequestBody().readAllBytes();
            String key = exchange.getRequestMethod() + " " + path;
            String required = requiredTokens.get(key);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            Answer answer = required != null && !("Bearer " + required).equals(auth)
                    ? new Answer(401, "application/json", "{\"error\":\"invalid bearer token\"}")
                    : answers.getOrDefault(key,
                            new Answer(404, "application/json", "{\"detail\":\"Not Found\"}"));
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", answer.contentType());
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** Answers the Rust engram-server's {@code GET /health}; everything else 404s. */
    static StubEngramServer rust() throws IOException {
        StubEngramServer stub = new StubEngramServer();
        stub.answer("GET", "/health", 200, "{\"status\":\"ok\",\"service\":\"engram\"}");
        return stub;
    }

    /** Answers like the Python Engram server: {@code /healthz}, no {@code /health}. */
    static StubEngramServer python() throws IOException {
        StubEngramServer stub = new StubEngramServer();
        stub.answer("GET", "/healthz", 200, "{\"status\":\"ok\"}");
        return stub;
    }

    static StubEngramServer empty() throws IOException {
        return new StubEngramServer();
    }

    StubEngramServer answer(String method, String path, int status, String json) {
        answers.put(method + " " + path, new Answer(status, "application/json", json));
        return this;
    }

    /** Answers 401 on {@code method path} unless the request carries this bearer token. */
    StubEngramServer requireToken(String method, String path, String token) {
        requiredTokens.put(method + " " + path, token);
        return this;
    }

    StubEngramServer answerHtml(String method, String path) {
        answers.put(method + " " + path,
                new Answer(200, "text/html", "<!doctype html><title>app</title>"));
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
