package dev.jamjet.spring.approval;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;

/**
 * The authenticated principal on the current thread, from Spring Security's
 * SecurityContext when Spring Security is on the classpath. {@code null} when it
 * is absent, when nobody is authenticated, or for an anonymous authentication.
 */
public final class CurrentPrincipal {

    private static final boolean SECURITY_PRESENT = ClassUtils.isPresent(
            "org.springframework.security.core.context.SecurityContextHolder", CurrentPrincipal.class.getClassLoader());

    private CurrentPrincipal() {}

    public static boolean securityPresent() {
        return SECURITY_PRESENT;
    }

    public static String name() {
        return SECURITY_PRESENT ? Security.name() : null;
    }

    /** Only loaded when Spring Security is present. */
    private static final class Security {
        static String name() {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                return null;
            }
            String name = auth.getName();
            return name == null || name.isBlank() ? null : name;
        }
    }
}
