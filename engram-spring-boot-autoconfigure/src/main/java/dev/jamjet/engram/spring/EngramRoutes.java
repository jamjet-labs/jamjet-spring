package dev.jamjet.engram.spring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Engram server this starter talks to, and how it tells a route the
 * server does not serve apart from an outage.
 *
 * <p>The starter uses {@code EngramClient} from jamjet-sdk, which speaks the
 * REST API of the Rust {@code jamjet-engram-server} ({@code GET /health},
 * {@code POST /v1/memory}, {@code POST /v1/memory/context},
 * {@code /v1/memory/messages}). The Python Engram server ({@code jamjet-engram})
 * serves different routes ({@code /healthz}, {@code POST /v1/memory/record},
 * {@code GET /v1/memory/context}) and is not supported yet.
 */
final class EngramRoutes {

    static final String CONTEXT = "POST /v1/memory/context";
    static final String SAVE_MESSAGES = "POST /v1/memory/messages";
    static final String LIST_CONVERSATIONS = "GET /v1/memory/messages";
    static final String GET_MESSAGES = "GET /v1/memory/messages/{conversation_id}";
    static final String DELETE_MESSAGES = "DELETE /v1/memory/messages/{conversation_id}";

    static final String SUPPORTED_SERVER =
            "engram-spring-boot-starter talks to the Rust engram-server REST API "
            + "(jamjet-engram-server 0.5.x, started with \"engram serve --mode rest\"). "
            + "The Python Engram server (jamjet-engram) serves different routes and is not supported yet.";

    /**
     * The SDK reports a non-2xx answer as a RuntimeException whose message is
     * {@code "Engram API error: HTTP <status> for <path>: <body>"}.
     * EngramRouteNotServedTest pins this against the real client.
     */
    private static final Pattern SDK_HTTP_ERROR = Pattern.compile("^Engram API error: HTTP (\\d{3}) for ");

    private EngramRoutes() {}

    /**
     * The HTTP status if {@code error} says the server does not serve the
     * route (404 or 405), else -1. The Rust server never answers 404 or 405
     * from a handler, so either one means the route is missing.
     */
    static int notServedStatus(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            Matcher m = SDK_HTTP_ERROR.matcher(message);
            if (m.find()) {
                int status = Integer.parseInt(m.group(1));
                return status == 404 || status == 405 ? status : -1;
            }
        }
        return -1;
    }

    /** Rethrows {@code error} as {@link EngramServerMismatchException} if the route is not served. */
    static void throwIfNotServed(Exception error, String route, String baseUrl) {
        int status = notServedStatus(error);
        if (status < 0) {
            return;
        }
        StringBuilder message = new StringBuilder()
                .append("Engram server at ").append(baseUrl)
                .append(" answered HTTP ").append(status).append(" for ").append(route)
                .append(": it does not serve this route. ").append(SUPPORTED_SERVER);
        if (route.startsWith("GET /v1/memory/messages/") || route.startsWith("DELETE /v1/memory/messages/")) {
            message.append(" jamjet-engram-server 0.5.0 does not match ").append(route)
                    .append(" for any conversation id (it declares the route with a path syntax its "
                            + "router does not support), so EngramChatMemoryRepository cannot read or "
                            + "delete history on it. Set engram.spring-ai.chat-memory-repository=false "
                            + "and use another ChatMemoryRepository until a fixed server is released.");
        }
        throw new EngramServerMismatchException(message.toString(), error);
    }
}
