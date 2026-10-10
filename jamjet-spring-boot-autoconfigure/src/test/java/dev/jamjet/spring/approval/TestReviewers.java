package dev.jamjet.spring.approval;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.security.Principal;

/** Authenticated principals as Spring Security would hand them to the controller. */
public final class TestReviewers {

    private TestReviewers() {}

    /** An authenticated user holding the default approver authority. */
    public static Principal approver(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a",
                AuthorityUtils.createAuthorityList(ReviewPolicy.DEFAULT_AUTHORITY));
    }

    /** An authenticated user with only the given authorities. */
    public static Principal user(String name, String... authorities) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", AuthorityUtils.createAuthorityList(authorities));
    }
}
