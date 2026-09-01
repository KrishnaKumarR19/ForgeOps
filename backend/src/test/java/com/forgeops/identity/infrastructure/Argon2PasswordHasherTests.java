package com.forgeops.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.PasswordHasher;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Argon2id password hasher (Phase 4.2 Slice 1). Pure unit tests — no
 * Spring context and no database. Synthetic test passwords only; no real credentials.
 */
class Argon2PasswordHasherTests {

    private final PasswordHasher hasher = new Argon2PasswordHasher();

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @Test
    void producesAnArgon2idHashThatIsNotPlaintext() {
        PasswordHash hash = hasher.hash(PASSWORD);

        assertThat(hash.encodedValue()).isNotEqualTo(PASSWORD);
        assertThat(hash.encodedValue()).startsWith("$argon2id$");
    }

    @Test
    void verifiesCorrectPassword() {
        PasswordHash hash = hasher.hash(PASSWORD);

        assertThat(hasher.verify(PASSWORD, hash)).isTrue();
    }

    @Test
    void rejectsIncorrectPassword() {
        PasswordHash hash = hasher.hash(PASSWORD);

        assertThat(hasher.verify("WrongPassword", hash)).isFalse();
    }

    @Test
    void usesIndependentSaltsPerHash() {
        PasswordHash first = hasher.hash(PASSWORD);
        PasswordHash second = hasher.hash(PASSWORD);

        // Same password, different encoded values → per-hash random salt (not a fixed salt).
        assertThat(first.encodedValue()).isNotEqualTo(second.encodedValue());
        // Both still verify against the original password.
        assertThat(hasher.verify(PASSWORD, first)).isTrue();
        assertThat(hasher.verify(PASSWORD, second)).isTrue();
    }

    @Test
    void malformedHashDoesNotVerify() {
        // A PasswordHash whose encoded value is well-formed enough to wrap ($-prefixed) but
        // not a valid Argon2 encoding must never verify as a success.
        PasswordHash malformed = PasswordHash.ofEncoded("$argon2id$not-a-real-hash");

        assertThat(hasher.verify(PASSWORD, malformed)).isFalse();
    }

    @Test
    void nullInputsDoNotVerify() {
        PasswordHash hash = hasher.hash(PASSWORD);

        assertThat(hasher.verify(null, hash)).isFalse();
        assertThat(hasher.verify(PASSWORD, null)).isFalse();
    }
}
