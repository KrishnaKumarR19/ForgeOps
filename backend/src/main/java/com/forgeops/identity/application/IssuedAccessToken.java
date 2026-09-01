package com.forgeops.identity.application;

/**
 * The result of issuing an access token: the encoded JWT and its lifetime in seconds.
 * Carries no secret beyond the token itself and is safe to return to the client
 * (mapped to the API login response).
 *
 * @param token          the encoded RS256 JWT access token
 * @param expiresInSeconds seconds until the token expires (exp - iat)
 */
public record IssuedAccessToken(String token, long expiresInSeconds) {
}
