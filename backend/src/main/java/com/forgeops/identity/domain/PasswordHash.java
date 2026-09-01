package com.forgeops.identity.domain;

import java.util.Objects;

/**
 * A value object wrapping an <strong>already-encoded</strong> password hash.
 *
 * <p>Security boundary (SECURITY_DESIGN.md §4, ADR-0031): this type exists so the domain
 * cannot accidentally hold or persist a plaintext password. It is constructed only from an
 * encoded hash string (produced by the hashing component in Phase 4.2), and it only
 * accepts values in a recognizable encoded format (e.g. a PHC/modular-crypt string such as
 * {@code $argon2id$...}). Plaintext cannot be wrapped by this type.
 *
 * <p>The raw encoded value is exposed only for persistence via {@link #encodedValue()} and
 * is deliberately excluded from {@link #toString()} so it is never logged.
 */
public final class PasswordHash {

    private final String encoded;

    private PasswordHash(String encoded) {
        this.encoded = encoded;
    }

    /**
     * Wraps an already-encoded hash. Rejects null/blank values and values that are not in a
     * modular-crypt/PHC encoded form (which begins with {@code $}). This makes it
     * structurally difficult to pass a plaintext password where a hash is expected. No
     * hashing is performed here (that is Phase 4.2).
     */
    public static PasswordHash ofEncoded(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Encoded password hash must not be blank");
        }
        if (!encoded.startsWith("$")) {
            throw new IllegalArgumentException(
                    "Value does not look like an encoded password hash; plaintext is not permitted");
        }
        return new PasswordHash(encoded);
    }

    /** The encoded hash string, for persistence only. Never log this value. */
    public String encodedValue() {
        return encoded;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PasswordHash that)) return false;
        return encoded.equals(that.encoded);
    }

    @Override
    public int hashCode() {
        return Objects.hash(encoded);
    }

    /** Deliberately does NOT include the encoded value, to avoid accidental logging. */
    @Override
    public String toString() {
        return "PasswordHash[REDACTED]";
    }
}
