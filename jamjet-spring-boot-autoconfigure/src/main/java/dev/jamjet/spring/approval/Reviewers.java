package dev.jamjet.spring.approval;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.security.Principal;

/** Decides whether a request principal is a reviewer, and why not. */
final class Reviewers {

    /** {@code name} when accepted; otherwise {@code status} and {@code reason}. */
    record Verdict(String name, int status, String reason) {
        boolean accepted() {
            return name != null;
        }

        static Verdict accept(String name) {
            return new Verdict(name, 200, null);
        }

        static Verdict refuse(int status, String reason) {
            return new Verdict(null, status, reason);
        }
    }

    private Reviewers() {}

    static Verdict check(Principal principal, ReviewPolicy policy) {
        if (policy.allowUnauthenticated()) {
            String name = principal == null || principal.getName() == null || principal.getName().isBlank()
                    ? JamjetApprovalController.UNAUTHENTICATED : principal.getName();
            return Verdict.accept(name);
        }
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return Verdict.refuse(401, "an authenticated reviewer is required to decide an approval");
        }
        if (CurrentPrincipal.securityPresent()) {
            Verdict security = Security.check(principal, policy);
            if (security != null) {
                return security;
            }
        }
        return Verdict.accept(principal.getName());
    }

    /** Only loaded when Spring Security is present. */
    private static final class Security {
        static Verdict check(Principal principal, ReviewPolicy policy) {
            if (!(principal instanceof Authentication auth)) {
                return null;
            }
            if (auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
                return Verdict.refuse(401, "an authenticated reviewer is required to decide an approval");
            }
            if (auth instanceof RememberMeAuthenticationToken && !policy.allowRememberMe()) {
                return Verdict.refuse(401, "a remember-me login cannot review approvals; sign in again "
                        + "(or set spring.jamjet.approval.allow-remember-me=true)");
            }
            return null;
        }
    }
}
