package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxCleanupProperties} defaulting/validation: null or non-positive
 * retention / delay / batch size fall back to the ForgeOps v1 defaults (7-day retention,
 * hourly cadence, 500-row batches); explicit valid values are honored; enabled defaults true.
 * No invalid value is ever accepted.
 */
class OutboxCleanupPropertiesTests {

    @Test
    void appliesV1DefaultsWhenUnset() {
        OutboxCleanupProperties props = new OutboxCleanupProperties(null, null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.retention()).isEqualTo(Duration.ofHours(168)); // 7 days
        assertThat(props.fixedDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(props.batchSize()).isEqualTo(500);
    }

    @Test
    void rejectsNonPositiveRetention() {
        assertThat(new OutboxCleanupProperties(true, Duration.ZERO, null, null).retention())
                .isEqualTo(Duration.ofHours(168));
        assertThat(new OutboxCleanupProperties(true, Duration.ofSeconds(-5), null, null).retention())
                .isEqualTo(Duration.ofHours(168));
    }

    @Test
    void rejectsNonPositiveFixedDelay() {
        assertThat(new OutboxCleanupProperties(true, null, Duration.ZERO, null).fixedDelay())
                .isEqualTo(Duration.ofHours(1));
        assertThat(new OutboxCleanupProperties(true, null, Duration.ofSeconds(-1), null).fixedDelay())
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThat(new OutboxCleanupProperties(true, null, null, 0).batchSize()).isEqualTo(500);
        assertThat(new OutboxCleanupProperties(true, null, null, -10).batchSize()).isEqualTo(500);
    }

    @Test
    void honoursExplicitValues() {
        OutboxCleanupProperties props = new OutboxCleanupProperties(
                false, Duration.ofDays(3), Duration.ofMinutes(30), 250);

        assertThat(props.enabled()).isFalse();
        assertThat(props.retention()).isEqualTo(Duration.ofDays(3));
        assertThat(props.fixedDelay()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.batchSize()).isEqualTo(250);
    }
}
