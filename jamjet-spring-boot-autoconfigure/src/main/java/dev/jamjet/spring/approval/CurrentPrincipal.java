package dev.jamjet.spring.approval;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;

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

    /**
     * The authenticated principal in the Reactor context (WebFlux,
     * {@code ReactiveSecurityContextHolder}); empty when there is none.
     */
    public static Mono<String> reactiveName() {
        return SECURITY_PRESENT ? Security.reactiveName() : Mono.empty();
    }

    /** Only loaded when Spring Security is present. */
    private static final class Security {
        static String name() {
            return nameOf(SecurityContextHolder.getContext().getAuthentication());
        }

        static Mono<String> reactiveName() {
            return ReactiveSecurityContextHolder.getContext()
                    .map(ctx -> {
                        String n = nameOf(ctx.getAuthentication());
                        return n == null ? "" : n;
                    })
                    .filter(n -> !n.isEmpty());
        }

        static String nameOf(org.springframework.security.core.Authentication auth) {
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                return null;
            }
            String name = auth.getName();
            return name == null || name.isBlank() ? null : name;
        }
    }
}
