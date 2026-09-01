package com.forgeops.identity.application;

/**
 * Raised when provisioning is asked to create a user whose username already exists.
 *
 * <p>PostgreSQL's {@code uq_users_username} unique constraint remains the authoritative
 * uniqueness boundary; this exception represents the application-level detection of that
 * condition (either via a pre-check or by translating a persistence violation).
 */
public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException(String username) {
        super("A user with username '" + username + "' already exists");
    }
}
