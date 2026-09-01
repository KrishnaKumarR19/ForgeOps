package com.forgeops.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * User-provisioning request body for {@code POST /api/v1/auth/register} (API_CONTRACTS.md
 * §4). This is an administrator-gated provisioning operation, not open self-registration
 * (ADR-0033) — authorization to ADMIN is enforced by the security filter chain, not here.
 *
 * <p>Only the fields the identity domain actually persists are accepted: {@code username},
 * {@code password}, and {@code roles}. The server always generates the user id and sets the
 * account status; the client cannot supply an id or status. The password is never echoed
 * back and only its Argon2id hash is stored.
 *
 * @param username the login username (must be unique; DB is authoritative)
 * @param password the plaintext password (hashed server-side, never stored/returned)
 * @param roles    the roles to assign; at least one is required
 */
public record RegisterRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotEmpty Set<String> roles) {
}
