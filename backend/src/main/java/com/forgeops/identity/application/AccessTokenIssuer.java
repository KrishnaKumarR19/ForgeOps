package com.forgeops.identity.application;

import com.forgeops.identity.domain.User;

/**
 * Application port for issuing an access token for an authenticated user.
 *
 * <p>The JWT/RS256 cryptographic implementation lives in the infrastructure layer
 * (ADR-0030); the application depends only on this port. The issuer derives all claims
 * ({@code sub}, {@code roles}, {@code iss}, {@code aud}, {@code iat}, {@code exp},
 * {@code jti}) from the server-side {@link User} and configuration — never from client
 * input.
 */
public interface AccessTokenIssuer {

    /** Issues a short-lived RS256 access token for the given (already authenticated) user. */
    IssuedAccessToken issueFor(User user);
}
