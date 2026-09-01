package com.forgeops.identity.application;

/**
 * Application port for validating an encoded access token and extracting the trusted claims
 * (Phase 4.2 Slice 4). The infrastructure adapter performs the cryptographic work (RS256
 * signature verification with the configured public key) and enforces the token contract
 * (issuer, audience, expiration, required claims) — but the application depends only on
 * this abstraction, not on the JWT library (ADR-0030).
 *
 * <p>This port performs <strong>token</strong> validation only. Resolving the token to a
 * currently-active persisted user (and rejecting unknown/deactivated accounts) is a
 * separate concern handled by {@link AuthenticationService}.
 */
public interface AccessTokenValidator {

    /**
     * Validates the encoded token and returns its trusted claims.
     *
     * @param encodedToken the raw JWT string extracted from the Bearer header
     * @return the validated claims (never {@code null})
     * @throws InvalidAccessTokenException if the token is not cryptographically valid,
     *                                     uses a disallowed algorithm, fails issuer/
     *                                     audience/expiration checks, or is missing a
     *                                     required claim
     */
    ValidatedAccessToken validate(String encodedToken);
}
