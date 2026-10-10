package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.JamjetRecordingException;
import dev.jamjet.spring.RuntimeErrorMode;
import dev.jamjet.spring.client.JamjetClientException;
import org.slf4j.Logger;

/** Applies a {@link RuntimeErrorMode} to a failed runtime call. */
final class Failures {

    private Failures() {}

    static void handle(RuntimeErrorMode mode, Logger log, String action, JamjetClientException e) {
        if (mode == RuntimeErrorMode.FAIL_OPEN) {
            log.warn("JamJet runtime could not {}; continuing because the error mode is fail-open: {}",
                    action, e.getMessage());
            return;
        }
        throw new JamjetGovernanceException("JamJet runtime could not " + action + ": " + e.getMessage()
                + " (use RuntimeErrorMode.FAIL_OPEN to continue without the runtime)", e);
    }

    static JamjetRecordingException recordingFailure(String action, JamjetClientException e) {
        return new JamjetRecordingException("The call completed but the JamJet runtime could not " + action
                + ": " + e.getMessage() + ". The call ran; only its record is missing.", e);
    }
}
