package com.forgeops.identity.infrastructure;

import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id implementation of the {@link PasswordHasher} port (ADR-0031, SECURITY_DESIGN.md
 * §4–§5), backed by Spring Security Crypto's {@link Argon2PasswordEncoder}.
 *
 * <p>The encoder produces a PHC/modular-crypt encoded string ({@code $argon2id$...}) with a
 * unique random salt per hash, which is wrapped in a {@link PasswordHash}. Verification uses
 * the encoder's {@code matches()} (constant-time, salt read from the encoded value) — there
 * is no manual hash comparison, no reversible encryption, and no custom crypto.
 *
 * <p>Parameters use the SECURITY_DESIGN.md §5 local/CI baseline: 16-byte salt, 32-byte hash,
 * parallelism 1, memory 19456 KiB (19 MiB), 2 iterations. Production tuning is a later,
 * measured concern, as the design states.
 */
@Component
class Argon2PasswordHasher implements PasswordHasher {

    // SECURITY_DESIGN.md §5 baseline.
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19456; // 19 MiB
    private static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(
            SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

    @Override
    public PasswordHash hash(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.length() == 0) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        String encoded = encoder.encode(rawPassword);
        return PasswordHash.ofEncoded(encoded);
    }

    @Override
    public boolean verify(CharSequence rawPassword, PasswordHash passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        // Argon2PasswordEncoder.matches() safely returns false for a malformed/unrecognized
        // encoded value; it does not throw on bad input and never returns true on error.
        return encoder.matches(rawPassword, passwordHash.encodedValue());
    }
}
