package com.forgeops.identity.infrastructure.security;

import com.forgeops.identity.application.AuthenticatedUser;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Adapts the application-layer {@link AuthenticatedUser} principal to Spring Security's
 * {@link org.springframework.security.core.Authentication} contract so it can live in the
 * {@code SecurityContext} (Phase 4.2 Slice 4).
 *
 * <p>This adapter lives in the infrastructure/security layer precisely so that Spring
 * Security types do not leak into the application or domain (ADR-0030). It is always
 * authenticated (it is only created after successful validation + resolution) and carries
 * no credentials.
 *
 * <p>This slice establishes authentication only: no {@link GrantedAuthority} entries are
 * derived from roles here, because role-based authorization is the next slice. The
 * authenticated principal's roles remain available via {@link #principal()} for that later
 * work.
 */
final class AuthenticatedUserAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;

    AuthenticatedUserAuthentication(AuthenticatedUser principal) {
        // No authorities are granted in this slice (authentication only, not authorization).
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    AuthenticatedUser principal() {
        return principal;
    }

    @Override
    public Collection<GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** No credentials are retained after authentication. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }
}
