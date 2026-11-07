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
     * Clears the interrupt flag if the thread was not interrupted before the
     * call and {@code error} is not about an interrupt.
     */
    static void undo(Throwable error, boolean interruptedBeforeCall) {
        if (interruptedBeforeCall) {
            return;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return;
            }
        }
        Thread.interrupted();
    }
}
