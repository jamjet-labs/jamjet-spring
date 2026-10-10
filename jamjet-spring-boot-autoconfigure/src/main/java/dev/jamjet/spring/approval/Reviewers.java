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
        String authority = policy.requiredAuthority();
        if (authority != null && !authority.isBlank()
                && !(CurrentPrincipal.securityPresent() && Security.holds(principal, authority))) {
            return Verdict.refuse(403, "approving requires the " + authority + " authority "
                    + "(spring.jamjet.approval.required-authority)");
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

        /** True only for an Authentication granted this authority; anything else cannot be checked. */
        static boolean holds(Principal principal, String authority) {
            return principal instanceof Authentication auth && auth.getAuthorities() != null
                    && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
        }
    }
}
