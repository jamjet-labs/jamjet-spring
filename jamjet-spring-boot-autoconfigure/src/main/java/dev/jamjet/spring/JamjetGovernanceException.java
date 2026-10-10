package dev.jamjet.spring;

/**
 * Thrown by a JamJet advisor running with
 * {@code spring.jamjet.on-runtime-error=fail-closed} (the default) when the
 * runtime could not record a governed call, so the call is refused rather than
 * run ungoverned. The cause carries the runtime's error.
 */
public class JamjetGovernanceException extends RuntimeException {

    public JamjetGovernanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
