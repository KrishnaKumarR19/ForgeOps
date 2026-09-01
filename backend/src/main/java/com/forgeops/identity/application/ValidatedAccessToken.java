package com.forgeops.identity.application;

import com.forgeops.identity.domain.Role;
import java.util.Set;
import java.util.UUID;

/**
 * The result of successfully validating an access token: the claims that authentication
 * trusts, expressed in domain terms. This is an application-layer value object — it carries
 * no Nimbus/JWT implementation types, so the cryptographic library stays behind the
 * infrastructure boundary (ADR-0030).
 *
 * <p>Only the claims relevant to authentication are surfaced: the server-established
 * subject ({@code sub}) as the persisted user id, the roles asserted by the token
 * (authoritative for the token's lifetime per SECURITY_DESIGN.md §12), and the token id
 * ({@code jti}). No signature material, header, or raw token text is retained here.
 *
 * @param userId the persisted user id parsed from {@code sub}
 * @param roles  the roles asserted by the token
 * @param jwtId  the token identifier ({@code jti})
 */
public record ValidatedAccessToken(UUID userId, Set<Role> roles, String jwtId) {

    public ValidatedAccessToken {
        if (userId == null) {
            throw new IllegalArgumentException("userId (sub) is required");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
