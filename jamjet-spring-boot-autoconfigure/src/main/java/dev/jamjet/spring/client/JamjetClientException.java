package dev.jamjet.spring.client;

/**
 * Exception thrown when the JamJet runtime API returns an error response.
 */
public class JamjetClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final String path;

    public JamjetClientException(int statusCode, String responseBody, String path) {
        super("JamJet API error %d on %s: %s".formatted(statusCode, path, responseBody));
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

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getPath() {
        return path;
    }

    /** True if this was a 5xx server error (may be worth retrying). */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /** True if this was a 401/403 auth error. */
    public boolean isAuthError() {
        return statusCode == 401 || statusCode == 403;
    }
}
