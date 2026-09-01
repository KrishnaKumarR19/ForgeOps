package com.forgeops.identity.infrastructure.security;

import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.domain.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Adapts the application-layer {@link AuthenticatedUser} principal to Spring Security's
 * {@link org.springframework.security.core.Authentication} contract so it can live in the
 * {@code SecurityContext} (Phase 4.2 Slice 4 / authorities added in Slice 5).
 *
 * <p>This adapter lives in the infrastructure/security layer precisely so that Spring
 * Security types do not leak into the application or domain (ADR-0030). It is always
 * authenticated (it is only created after successful validation + resolution) and carries
 * no credentials.
 *
 * <p>Authorization (Slice 5): each {@link Role} on the authenticated principal is mapped to a
 * single Spring authority named {@code ROLE_<name>} (e.g. {@code ROLE_ADMIN}). This is the
 * canonical form expected by {@code hasRole(...)} — the {@code ROLE_} prefix is added exactly
 * once here, so URL rules use {@code hasRole("ADMIN")} without any doubled prefix. The roles
 * come solely from the validated-token-derived principal; no client input contributes.
 */
final class AuthenticatedUserAuthentication extends AbstractAuthenticationToken {

    /** The single, canonical Spring authority prefix for a role (matches {@code hasRole}). */
    static final String ROLE_PREFIX = "ROLE_";

    private final AuthenticatedUser principal;

    AuthenticatedUserAuthentication(AuthenticatedUser principal) {
        super(toAuthorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> toAuthorities(AuthenticatedUser principal) {
        return principal.roles().stream()
                .map(Role::name)
                .sorted()
                .map(name -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + name))
                .toList();
    }

    AuthenticatedUser principal() {
        return principal;
    }

    @Override
    public Collection<GrantedAuthority> getAuthorities() {
        return super.getAuthorities();
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
