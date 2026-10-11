package dev.jamjet.spring.advisor;

/**
 * The principal that triggered a call, as captured by JamJet from the security
 * context. Package-private on purpose: application code cannot construct one, so
 * an advisor param cannot name (or launder away) the initiator. {@code name} is
 * null when the initiator is unknown.
 */
record CapturedInitiator(String name) {
}
