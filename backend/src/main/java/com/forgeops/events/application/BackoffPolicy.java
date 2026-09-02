package com.forgeops.events.application;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Deterministic, bounded exponential backoff for outbox retries (Phase 6 Slice 2). For attempt
 * number {@code n} (1-based, i.e. the delay to apply after the n-th failed attempt), the delay
 * is {@code min(baseDelay * 2^(n-1), maxDelay)}.
 *
 * <p>Overflow-safe: the exponent is computed in a way that never overflows {@code long}
 * milliseconds — once the shift would exceed the cap it returns {@code maxDelay} directly, so
 * a large {@code attempts} value stays deterministic and bounded. No jitter (not required by
 * the architecture).
 */
@Component
public class BackoffPolicy {

    private final long baseMillis;
    private final long maxMillis;

    public BackoffPolicy(OutboxPublisherProperties properties) {
        this.baseMillis = Math.max(1L, properties.backoff().baseDelay().toMillis());
        this.maxMillis = Math.max(baseMillis, properties.backoff().maxDelay().toMillis());
    }

    /**
     * Delay to apply after {@code attempts} failed attempts (attempts >= 1).
     */
    public Duration delayForAttempt(int attempts) {
        int n = Math.max(1, attempts);
        // Number of doublings; cap the shift so 2^shift * base cannot overflow before the cap.
        int shift = n - 1;
        // Once base << shift would reach/exceed the cap, just return the cap. 62 guards the
        // long shift itself; the multiplicative check guards the value.
        if (shift >= 62) {
            return Duration.ofMillis(maxMillis);
        }
        long factor = 1L << shift; // 2^(n-1), safe for shift < 62
        // Guard the multiplication against overflow before comparing to the cap.
        if (factor > maxMillis / baseMillis) {
            return Duration.ofMillis(maxMillis);
        }
        long delay = Math.min(baseMillis * factor, maxMillis);
        return Duration.ofMillis(delay);
    }
}
