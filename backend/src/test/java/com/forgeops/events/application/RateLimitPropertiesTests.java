package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Validation/defaulting rules for {@link RateLimitProperties} (Phase 8 Slice 1, FR-RL-6). A
 * misconfiguration must never disable protection by yielding an unlimited allowance or a
 * zero/negative window.
 */
class RateLimitPropertiesTests {

    @Test
    void nullsFallBackToProductionDefaults() {
        RateLimitProperties p = new RateLimitProperties(null, null, null);
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.limit()).isEqualTo(RateLimitProperties.DEFAULT_LIMIT);
        assertThat(p.window()).isEqualTo(RateLimitProperties.DEFAULT_WINDOW);
    }

    @Test
    void explicitValuesAreHonored() {
        RateLimitProperties p = new RateLimitProperties(true, 10, Duration.ofSeconds(30));
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.limit()).isEqualTo(10);
        assertThat(p.window()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void disabledIsHonored() {
        RateLimitProperties p = new RateLimitProperties(false, 10, Duration.ofSeconds(30));
        assertThat(p.isEnabled()).isFalse();
    }

    @Test
    void nonPositiveLimitFallsBackToDefault() {
        assertThat(new RateLimitProperties(true, 0, Duration.ofMinutes(1)).limit())
                .isEqualTo(RateLimitProperties.DEFAULT_LIMIT);
        assertThat(new RateLimitProperties(true, -5, Duration.ofMinutes(1)).limit())
                .isEqualTo(RateLimitProperties.DEFAULT_LIMIT);
    }

    @Test
    void zeroOrNegativeWindowFallsBackToDefault() {
        assertThat(new RateLimitProperties(true, 10, Duration.ZERO).window())
                .isEqualTo(RateLimitProperties.DEFAULT_WINDOW);
        assertThat(new RateLimitProperties(true, 10, Duration.ofSeconds(-1)).window())
                .isEqualTo(RateLimitProperties.DEFAULT_WINDOW);
    }
}
