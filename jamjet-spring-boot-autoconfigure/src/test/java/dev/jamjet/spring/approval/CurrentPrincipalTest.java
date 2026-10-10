package dev.jamjet.spring.approval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentPrincipalTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAuthenticatedUserIsThePrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("carol", "n/a", List.of()));
        assertThat(CurrentPrincipal.name()).isEqualTo("carol");
    }

    @Test
    void anAnonymousAuthenticationIsNobody() {
        // Spring Security marks unauthenticated requests with an anonymous token
        // whose name is "anonymousUser"; that must never count as a person.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(CurrentPrincipal.name()).isNull();
    }

    @Test
    void anUnauthenticatedTokenIsNobody() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("mallory", "pw"));
        assertThat(CurrentPrincipal.name()).isNull();
    }

    @Test
    void noAuthenticationIsNobody() {
        assertThat(CurrentPrincipal.name()).isNull();
    }
}
