package dev.jamjet.engram.spring;

/**
 * Error text that is safe to log or show in health details.
 *
 * <p>When the JDK refuses a header value it throws an
 * {@link IllegalArgumentException} whose message holds the whole header, for
 * a bearer token the token itself. An application can build its own
 * {@code EngramClient} with any token, so the starter never passes such a
 * message on.
 */
final class EngramErrors {

    static final String HEADER_REFUSED =
            "the request could not be built: an HTTP header value was refused (not shown, it may hold the token)";

    private EngramErrors() {}

    static boolean isRefusedHeader(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                return true;
            }
        }
        return false;
    }

    static String safeMessage(Throwable error) {
        return isRefusedHeader(error) ? HEADER_REFUSED : error.getMessage();
    }
}
