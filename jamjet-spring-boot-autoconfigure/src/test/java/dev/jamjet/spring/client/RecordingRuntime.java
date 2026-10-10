package dev.jamjet.spring.client;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A local HTTP server that records every request and answers with canned
 * responses keyed by exact {@code METHOD path} (query string included).
 *
 * <p>Unmatched requests get what the real runtime gives an unknown path: the
 * SPA fallback, {@code 200 text/html}. A client that calls a route the runtime
 * does not serve therefore fails here exactly as it fails in production.
 */
public final class RecordingRuntime implements AutoCloseable {

    public record Request(String method, String path, String body, Map<String, List<String>> headers) {
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private record Canned(int status, String contentType, String body, long delayMillis) {}

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final Map<String, Runnable> triggers = new ConcurrentHashMap<>();

    public RecordingRuntime() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String full = query == null ? path : path + "?" + query;
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), full, body,
                    Map.copyOf(exchange.getRequestHeaders())));
            Runnable trigger = triggers.get(exchange.getRequestMethod() + " " + full);
            if (trigger != null) {
                trigger.run();
            }
            Canned canned = routes.getOrDefault(exchange.getRequestMethod() + " " + full,
                    new Canned(200, "text/html", "<!doctype html><title>jamjet</title>", 0));
            if (canned.delayMillis() > 0) {
                try {
                    Thread.sleep(canned.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = canned.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", canned.contentType());
            exchange.sendResponseHeaders(canned.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    /** Answer {@code method path} with {@code status} and a JSON body. */
    public RecordingRuntime on(String method, String path, int status, String json) {
        routes.put(method + " " + path, new Canned(status, "application/json", json, 0));
        return this;
    }

    /** As {@link #on}, answering only after {@code delayMillis}. */
    public RecordingRuntime onSlow(String method, String path, int status, String json, long delayMillis) {
        routes.put(method + " " + path, new Canned(status, "application/json", json, delayMillis));
        return this;
    }

    /** Run {@code action} when {@code method path} arrives, before it is answered. */
    public RecordingRuntime whenReceived(String method, String path, Runnable action) {
        triggers.put(method + " " + path, action);
        return this;
    }

    public long count(String method, String path) {
        return requests().stream().filter(r -> r.method().equals(method) && r.path().equals(path)).count();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request last() {
        synchronized (requests) {
            if (requests.isEmpty()) {
                throw new AssertionError("no request reached the runtime");
            }
            return requests.get(requests.size() - 1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
