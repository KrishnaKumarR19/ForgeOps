package com.forgeops.identity.application;

/**
 * Raised when an access token cannot be trusted for authentication: bad or missing
 * signature, disallowed algorithm, wrong issuer/audience, expired, or missing/malformed
 * required claims.
 *
 * <p>The message is intentionally generic and MUST never contain token contents, claim
 * values, signatures, or key material (SECURITY_DESIGN.md §17). Authentication maps this to
 * a uniform {@code 401}; the specific reason is not disclosed to the client.
 */
public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException(String message) {
        super(message);
    }
}
