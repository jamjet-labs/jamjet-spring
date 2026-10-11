package dev.jamjet.spring.approval;

/**
 * Who may review a paused call, for {@link JamjetApprovalController}.
 *
 * @param allowUnauthenticated  accept decisions without a principal or authority (local development only)
 * @param allowRememberMe       accept a remember-me login as a reviewer
 * @param requiredAuthority     authority a reviewer must hold; blank turns the check off
 * @param allowUnknownInitiator approve a call whose initiator could not be determined, so
 *                              separation of duties cannot be checked
 */
public record ReviewPolicy(boolean allowUnauthenticated, boolean allowRememberMe, String requiredAuthority,
                           boolean allowUnknownInitiator) {

    /** The authority a reviewer needs by default. */
    public static final String DEFAULT_AUTHORITY = "ROLE_JAMJET_APPROVER";

    /** Authenticated reviewers holding {@link #DEFAULT_AUTHORITY}; remember-me and unknown initiators refused. */
    public static ReviewPolicy defaults() {
        return new ReviewPolicy(false, false, DEFAULT_AUTHORITY, false);
    }

    public ReviewPolicy withAllowUnauthenticated(boolean value) {
        return new ReviewPolicy(value, allowRememberMe, requiredAuthority, allowUnknownInitiator);
    }

    public ReviewPolicy withAllowRememberMe(boolean value) {
        return new ReviewPolicy(allowUnauthenticated, value, requiredAuthority, allowUnknownInitiator);
    }

    public ReviewPolicy withRequiredAuthority(String value) {
        return new ReviewPolicy(allowUnauthenticated, allowRememberMe, value, allowUnknownInitiator);
    }

    public ReviewPolicy withAllowUnknownInitiator(boolean value) {
        return new ReviewPolicy(allowUnauthenticated, allowRememberMe, requiredAuthority, value);
    }
}
