package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BackoffPolicy}: deterministic capped exponential backoff, and
 * overflow-safety for large attempt counts. Defaults: base 5s, cap 5m.
 */
class BackoffPolicyTests {

    private BackoffPolicy policy(Duration base, Duration max) {
        return new BackoffPolicy(new OutboxPublisherProperties(
                null, 0, new OutboxPublisherProperties.Backoff(base, max)));
    }

    private final BackoffPolicy defaults = policy(Duration.ofSeconds(5), Duration.ofMinutes(5));

    @Test
    void firstAttemptUsesBaseDelay() {
        assertThat(defaults.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void doublesEachAttemptUntilCap() {
        assertThat(defaults.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(defaults.delayForAttempt(5)).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    void isCappedAtMaxDelay() {
        // 5s * 2^6 = 320s > 300s cap
        assertThat(defaults.delayForAttempt(7)).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.delayForAttempt(50)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void isOverflowSafeForVeryLargeAttempts() {
        // Must not overflow or throw; returns the cap deterministically.
        assertThat(defaults.delayForAttempt(1000)).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.delayForAttempt(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void attemptsBelowOneTreatedAsFirstAttempt() {
        assertThat(defaults.delayForAttempt(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.delayForAttempt(-3)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void isDeterministic() {
        assertThat(defaults.delayForAttempt(3)).isEqualTo(defaults.delayForAttempt(3));
    }
}
