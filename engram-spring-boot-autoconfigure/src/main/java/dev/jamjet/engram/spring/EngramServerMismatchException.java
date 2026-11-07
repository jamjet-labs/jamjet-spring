package dev.jamjet.engram.spring;

/**
 * The server at {@code engram.base-url} is not one this starter can talk to:
 * it does not serve a route the starter needs, it is not an Engram server at
 * all, or it refused the configured token.
 *
 * <p>This is a deployment mistake, not an outage, so it is thrown instead of
 * being logged and skipped. The message names the server, the route and what
 * to change.
 */
public class EngramServerMismatchException extends IllegalStateException {

    public EngramServerMismatchException(String message) {
        super(message);
    }

    public EngramServerMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
