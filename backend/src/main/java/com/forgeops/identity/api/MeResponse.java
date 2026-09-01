package com.forgeops.identity.api;

import java.util.List;

/**
 * Current-identity response for {@code GET /api/v1/auth/me} (API_CONTRACTS.md §4): the
 * authenticated user's id and roles. No secret, password hash, or token material is exposed.
 *
 * <p>The roles reflect those asserted by the validated access token (authoritative for the
 * token's lifetime, SECURITY_DESIGN.md §12). This endpoint performs no authorization; it
 * simply reports the authenticated principal.
 *
 * @param id    the authenticated user id (the token {@code sub})
 * @param roles the roles carried by the authenticated principal
 */
public record MeResponse(String id, List<String> roles) {
}
