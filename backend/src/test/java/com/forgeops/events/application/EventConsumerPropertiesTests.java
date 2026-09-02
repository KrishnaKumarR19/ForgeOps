package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventConsumerProperties} defaulting: null/invalid values fall back to
 * safe deterministic defaults (enabled, single consumer, bounded 3-attempt retry with
 * exponential backoff bounds). No magic values leak through.
 */
class EventConsumerPropertiesTests {

    @Test
    void appliesSafeDefaultsWhenUnset() {
        EventConsumerProperties props = new EventConsumerProperties(null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.concurrency()).isEqualTo(1);
        assertThat(props.retry().maxAttempts()).isEqualTo(3);
        assertThat(props.retry().initialDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.retry().maxDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void clampsNonPositiveConcurrencyAndAttempts() {
        EventConsumerProperties props = new EventConsumerProperties(
                false, 0, new EventConsumerProperties.Retry(0, null, null));

        assertThat(props.enabled()).isFalse();
        assertThat(props.concurrency()).isEqualTo(1);   // 0 -> 1
        assertThat(props.retry().maxAttempts()).isEqualTo(3); // < 1 -> default 3
    }

    @Test
    void honoursExplicitValues() {
        EventConsumerProperties props = new EventConsumerProperties(
                true, 4, new EventConsumerProperties.Retry(
                        5, Duration.ofSeconds(1), Duration.ofSeconds(10)));

        assertThat(props.concurrency()).isEqualTo(4);
        assertThat(props.retry().maxAttempts()).isEqualTo(5);
        assertThat(props.retry().initialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(props.retry().maxDelay()).isEqualTo(Duration.ofSeconds(10));
    }
}
