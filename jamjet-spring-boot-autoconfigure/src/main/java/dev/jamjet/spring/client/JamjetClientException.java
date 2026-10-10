package dev.jamjet.spring.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thrown when a call to the JamJet runtime does not succeed: a non-2xx status,
 * a response that is not the runtime's JSON, or a transport failure.
 */
public class JamjetClientException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int statusCode;
    private final String responseBody;
    private final String path;

    public JamjetClientException(int statusCode, String responseBody, String path) {
        super("JamJet runtime returned %d on %s: %s".formatted(statusCode, path,
                messageOf(responseBody)));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.path = path;
    }

    public JamjetClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
        this.path = null;
    }

    public JamjetClientException(String message, String path) {
        super(message);
        this.statusCode = -1;
        this.responseBody = null;
        this.path = path;
    }

    /** HTTP status, or -1 when no runtime status applies (transport or protocol failure). */
    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getPath() {
        return path;
    }

    /**
     * The runtime's own error text: the {@code error} field of its JSON error
     * body, the raw body if it is not that shape, or this exception's message
     * when there was no response at all.
     */
    public String getRuntimeMessage() {
        if (responseBody == null) {
            return getMessage();
        }
        return messageOf(responseBody);
    }

    /** True if this was a 4xx answer from the runtime. */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /** True if this was a 5xx server error (may be worth retrying). */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /** True if this was a 401/403 auth error. */
    public boolean isAuthError() {
        return statusCode == 401 || statusCode == 403;
    }

    private static String messageOf(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node != null && node.hasNonNull("error")) {
                return node.get("error").asText();
            }
        } catch (Exception ignored) {
            // not JSON: fall through to the raw body
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
