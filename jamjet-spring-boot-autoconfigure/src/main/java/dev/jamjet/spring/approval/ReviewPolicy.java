package dev.jamjet.spring.approval;

/**
 * Who may review a paused call, for {@link JamjetApprovalController}.
 *
 * @param allowUnauthenticated accept decisions without a principal (local development only)
 * @param allowRememberMe      accept a remember-me login as a reviewer
 */
public record ReviewPolicy(boolean allowUnauthenticated, boolean allowRememberMe) {

    /** Authenticated reviewers only; remember-me logins refused. */
    public static ReviewPolicy defaults() {
        return new ReviewPolicy(false, false);
    }

    public ReviewPolicy withAllowUnauthenticated(boolean value) {
        return new ReviewPolicy(value, allowRememberMe);
    }

    public ReviewPolicy withAllowRememberMe(boolean value) {
        return new ReviewPolicy(allowUnauthenticated, value);
    }
}
