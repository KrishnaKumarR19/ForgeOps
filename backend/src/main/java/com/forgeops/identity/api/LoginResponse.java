package com.forgeops.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Successful login response (API_CONTRACTS.md §4): {@code {access_token, token_type,
 * expires_in}}. No user secret or internal domain object is exposed.
 *
 * @param accessToken the RS256 JWT access token
 * @param tokenType   always {@code "Bearer"}
 * @param expiresIn   seconds until the token expires
 */
public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn) {

    static LoginResponse bearer(String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, "Bearer", expiresIn);
    }
}
