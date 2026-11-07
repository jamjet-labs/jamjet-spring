package dev.jamjet.engram.spring;

/**
 * Undoes a side effect of jamjet-sdk 0.5.0's {@code EngramClient}: on any
 * {@code IOException} (a refused connection, a timeout) it interrupts the
 * calling thread before throwing. The next blocking call on that thread, for
 * example the model call the advisor is part of, then fails with an
 * {@code InterruptedException}. A real interrupt is kept.
 */
final class EngramSdkInterrupts {

    private EngramSdkInterrupts() {}

    /**
     * Clears the interrupt flag only when the thread was not interrupted
     * before the call and {@code error} is the SDK's wrapper around an
     * {@link java.io.IOException} ({@code "HTTP error for <path>"}), the one
     * path where the SDK interrupts the thread itself.
     */
    static void undo(Throwable error, boolean interruptedBeforeCall) {
        if (interruptedBeforeCall || error == null) {
            return;
        }
        String message = error.getMessage();
        if (message != null && message.startsWith("HTTP error for ")
                && error.getCause() instanceof java.io.IOException) {
            Thread.interrupted();
        }
    }
}
