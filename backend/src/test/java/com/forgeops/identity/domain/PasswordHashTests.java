package com.forgeops.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link PasswordHash} security boundary: it accepts only encoded hash
 * values, rejects plaintext-looking and blank values, and never reveals its value via
 * {@code toString()}.
 */
class PasswordHashTests {

    @Test
    void acceptsEncodedHash() {
        PasswordHash hash = PasswordHash.ofEncoded(
                "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$c29tZWhhc2h2YWx1ZQ");
        assertThat(hash.encodedValue()).startsWith("$argon2id$");
    }

    @Test
    void rejectsPlaintextLikeValue() {
        assertThatThrownBy(() -> PasswordHash.ofEncoded("hunter2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> PasswordHash.ofEncoded("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringDoesNotRevealHash() {
        PasswordHash hash = PasswordHash.ofEncoded(
                "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$c29tZWhhc2h2YWx1ZQ");
        assertThat(hash.toString()).doesNotContain("argon2id").contains("REDACTED");
    }
}
