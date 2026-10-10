package dev.jamjet.spring.approval;

/**
 * Who may review a paused call, for {@link JamjetApprovalController}.
 *
 * @param allowUnauthenticated accept decisions without a principal or authority (local development only)
 * @param allowRememberMe      accept a remember-me login as a reviewer
 * @param requiredAuthority    authority a reviewer must hold; blank turns the check off
 */
public record ReviewPolicy(boolean allowUnauthenticated, boolean allowRememberMe, String requiredAuthority) {

    /** The authority a reviewer needs by default. */
    public static final String DEFAULT_AUTHORITY = "ROLE_JAMJET_APPROVER";

    /** Authenticated reviewers holding {@link #DEFAULT_AUTHORITY}; remember-me logins refused. */
    public static ReviewPolicy defaults() {
        return new ReviewPolicy(false, false, DEFAULT_AUTHORITY);
    }

    public ReviewPolicy withAllowUnauthenticated(boolean value) {
        return new ReviewPolicy(value, allowRememberMe, requiredAuthority);
    }

    public ReviewPolicy withAllowRememberMe(boolean value) {
        return new ReviewPolicy(allowUnauthenticated, value, requiredAuthority);
    }

    public ReviewPolicy withRequiredAuthority(String value) {
        return new ReviewPolicy(allowUnauthenticated, allowRememberMe, value);
    }
}
