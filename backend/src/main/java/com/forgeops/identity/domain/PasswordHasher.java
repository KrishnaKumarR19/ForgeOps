package com.forgeops.identity.domain;

/**
 * Domain port for one-way password hashing and verification (Phase 4.2 Slice 1).
 *
 * <p>Framework-independent (ADR-0030): the domain declares the capability in terms of
 * domain types only; the Argon2id implementation lives in {@code identity.infrastructure}.
 * This preserves the {@link PasswordHash} boundary — hashing turns a plaintext password
 * into a {@link PasswordHash}, and verification checks a plaintext against a stored
 * {@link PasswordHash}. Plaintext never enters the persistent domain model.
 */
public interface PasswordHasher {

    /**
     * Hashes a plaintext password into an encoded {@link PasswordHash} (Argon2id).
     * The plaintext is used only transiently and is never stored.
     */
    PasswordHash hash(CharSequence rawPassword);

    /**
     * Verifies a plaintext password against a stored hash. Returns {@code false} for a
     * non-matching password and for a malformed/unrecognized stored hash — it never throws
     * to signal a mismatch and never returns {@code true} on error.
     */
    boolean verify(CharSequence rawPassword, PasswordHash passwordHash);
}
