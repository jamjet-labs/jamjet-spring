package dev.jamjet.engram.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class EngramSdkInterruptsTest {

    @AfterEach
    void clear() {
        Thread.interrupted();
    }

    private static RuntimeException sdkError(Throwable cause) {
        return new RuntimeException("HTTP error for /v1/memory/context", cause);
    }

    @Test
    void clearsTheInterruptTheSdkSetOnAnIoError() {
        Thread.currentThread().interrupt(); // what the SDK does on IOException
        EngramSdkInterrupts.undo(sdkError(new IOException("Connection refused")), false);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void keepsAnInterruptThatWasThereBeforeTheCall() {
        Thread.currentThread().interrupt();
        EngramSdkInterrupts.undo(sdkError(new IOException("Connection refused")), true);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void keepsAnInterruptWhenTheErrorIsNotAnSdkIoError() {
        // Only the SDK's "HTTP error for" wrapper around an IOException comes with
        // an interrupt the SDK set itself.
        Thread.currentThread().interrupt();
        EngramSdkInterrupts.undo(new RuntimeException("Engram API error: HTTP 500 for /x: {}"), false);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void keepsAnInterruptThatHappenedDuringTheCall() {
        Thread.currentThread().interrupt();
        EngramSdkInterrupts.undo(sdkError(new InterruptedException()), false);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
