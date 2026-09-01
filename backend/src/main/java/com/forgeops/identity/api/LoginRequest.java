package com.forgeops.identity.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request body (API_CONTRACTS.md §4). Only credentials are accepted; the client
 * cannot supply identity, roles, or any token parameter — those are server-determined.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
