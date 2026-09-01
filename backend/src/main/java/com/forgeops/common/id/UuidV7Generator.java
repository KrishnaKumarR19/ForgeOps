package com.forgeops.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generates time-ordered UUID v7 identifiers (ADR-0023), following the RFC 9562 layout:
 * a 48-bit big-endian Unix millisecond timestamp, the 4-bit version (7), the 2-bit
 * variant (0b10), and random bits filling the remainder.
 *
 * <p>Implemented without an external dependency to keep the foundation lean. Uses the
 * injected {@link Clock} for the timestamp so tests can make ids deterministic in time,
 * and {@link SecureRandom} for the random component.
 */
@Component
public class UuidV7Generator implements IdGenerator {

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID newId() {
        long timestampMillis = clock.millis();

        byte[] bytes = new byte[16];
        // 48-bit timestamp, big-endian, in the first 6 bytes.
        bytes[0] = (byte) (timestampMillis >>> 40);
        bytes[1] = (byte) (timestampMillis >>> 32);
        bytes[2] = (byte) (timestampMillis >>> 24);
        bytes[3] = (byte) (timestampMillis >>> 16);
        bytes[4] = (byte) (timestampMillis >>> 8);
        bytes[5] = (byte) timestampMillis;

        // Remaining 10 bytes random.
        byte[] rnd = new byte[10];
        random.nextBytes(rnd);
        System.arraycopy(rnd, 0, bytes, 6, 10);

        // Version 7 in the high nibble of byte 6.
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
        // Variant 0b10 in the two high bits of byte 8.
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        long mostSignificant = 0;
        long leastSignificant = 0;
        for (int i = 0; i < 8; i++) {
            mostSignificant = (mostSignificant << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            leastSignificant = (leastSignificant << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(mostSignificant, leastSignificant);
    }
}
