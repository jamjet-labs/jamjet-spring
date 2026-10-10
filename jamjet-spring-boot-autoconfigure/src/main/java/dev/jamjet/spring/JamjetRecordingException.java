package dev.jamjet.spring;

/**
 * Thrown when the model call has already run but JamJet could not write its
 * record (response, outcome). Distinct from {@link JamjetGovernanceException},
 * which means a call was refused before the model ran: here any effect of the
 * call has happened and only its record is missing. The cause carries the
 * runtime's error.
 */
public class JamjetRecordingException extends RuntimeException {

    public JamjetRecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
