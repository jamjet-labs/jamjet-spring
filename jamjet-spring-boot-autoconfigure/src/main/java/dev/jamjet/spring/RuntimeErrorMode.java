package dev.jamjet.spring;

/**
 * What a JamJet advisor does when the runtime cannot record a governed call.
 *
 * <p>Set with {@code spring.jamjet.on-runtime-error}.
 */
public enum RuntimeErrorMode {

    /**
     * Refuse the call: the advisor throws {@link JamjetGovernanceException} and
     * the ChatClient call fails. The default. A governance integration that
     * silently stops governing is the failure mode this exists to prevent.
     */
    FAIL_CLOSED,

    /**
     * Log a warning and let the call run without the missing record. Opt in
     * only where availability matters more than a complete record.
     */
    FAIL_OPEN
}
