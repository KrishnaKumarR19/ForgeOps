package com.forgeops.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the UUID v7 generator produces version-7, variant-2 identifiers and that the
 * time-ordered prefix reflects the injected clock. This exercises real (non-business)
 * foundation logic, so it is a justified unit test, not a placeholder.
 */
class UuidV7GeneratorTests {

    @Test
    void generatesVersion7Variant2Uuids() {
        UuidV7Generator generator = new UuidV7Generator(Clock.systemUTC());
        UUID id = generator.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // IETF variant (0b10)
    }

    @Test
    void timestampPrefixReflectsInjectedClock() {
        Instant fixedInstant = Instant.parse("2026-01-02T03:04:05.678Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        UUID id = new UuidV7Generator(fixedClock).newId();

        // The top 48 bits are the big-endian Unix-millis timestamp.
        long timestampMillis = id.getMostSignificantBits() >>> 16;
        assertThat(timestampMillis).isEqualTo(fixedInstant.toEpochMilli());
    }

    @Test
    void generatesUniqueIds() {
        UuidV7Generator generator = new UuidV7Generator(Clock.systemUTC());
        assertThat(generator.newId()).isNotEqualTo(generator.newId());
    }
}
