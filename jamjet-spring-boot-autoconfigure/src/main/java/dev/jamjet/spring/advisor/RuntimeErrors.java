package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.JamjetRecordingException;
import dev.jamjet.spring.client.JamjetClientException;
import org.slf4j.Logger;

/** Applies {@code spring.jamjet.on-runtime-error} to a failed runtime call. */
final class RuntimeErrors {

    private RuntimeErrors() {}

    /**
     * Fail closed (throw) unless the application opted into fail-open, in which
     * case log a warning and return so the caller can carry on.
     */
    static void handle(JamjetProperties properties, Logger log, String action, JamjetClientException e) {
        if (properties.isFailOpen()) {
            log.warn("JamJet runtime could not {}; continuing unrecorded because "
                    + "spring.jamjet.on-runtime-error=fail-open: {}", action, e.getMessage());
            return;
        }
        throw new JamjetGovernanceException("JamJet runtime could not " + action + ", so the call was refused: "
                + e.getMessage() + " (set spring.jamjet.on-runtime-error=fail-open to run calls the "
                + "runtime cannot record)", e);
    }

    /**
     * For a failure to record something AFTER the model ran. Fail-open logs;
     * fail-closed throws {@link JamjetRecordingException}, which says the call
     * completed, never that it was refused.
     */
    static void afterCall(JamjetProperties properties, Logger log, String action, JamjetClientException e) {
        if (properties.isFailOpen()) {
            log.warn("The model call completed but the JamJet runtime could not {}; continuing because "
                    + "spring.jamjet.on-runtime-error=fail-open: {}", action, e.getMessage());
            return;
        }
        throw recordingFailure(action, e);
    }

    static JamjetRecordingException recordingFailure(String action, JamjetClientException e) {
        return new JamjetRecordingException("The model call completed but the JamJet runtime could not " + action
                + ": " + e.getMessage() + ". The call ran; only its record is missing.", e);
    }
}
