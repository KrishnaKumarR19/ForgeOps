package com.forgeops.identity.application;

import com.forgeops.identity.domain.Role;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated principal exposed to the API/application layers after a request's
 * access token has been validated and resolved to a currently-active persisted user
 * (Phase 4.2 Slice 4).
 *
 * <p>The identity ({@link #userId()}) is the server-established {@code sub} — never a
 * client-supplied value. The {@link #roles()} are those asserted by the validated token,
 * which are authoritative for the token's short lifetime (SECURITY_DESIGN.md §12); this
 * slice performs no role-based authorization with them.
 *
 * <p>Deliberately free of Spring Security and Nimbus/JWT types so it can flow into the
 * application layer without leaking infrastructure concerns (ADR-0030).
 *
 * @param userId the authenticated persisted user id
 * @param roles  the roles asserted by the validated token
 */
public record AuthenticatedUser(UUID userId, Set<Role> roles) {

    public AuthenticatedUser {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
