package com.forgeops.events.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the outbox publisher (Phase 6 Slice 2). Poll cadence and batch size are
 * config-driven (ADR-0019 leaves intervals to implementation); backoff bounds are explicit and
 * deterministic (no magic constants). Safe non-secret defaults; overridable per environment.
 *
 * @param pollDelay  fixed delay between publisher poll cycles
 * @param batchSize  maximum number of due messages claimed per cycle
 * @param backoff    retry backoff bounds
 */
@ConfigurationProperties(prefix = "forgeops.outbox.publisher")
public record OutboxPublisherProperties(
        Duration pollDelay,
        int batchSize,
        Backoff backoff) {

    public OutboxPublisherProperties {
        pollDelay = pollDelay == null ? Duration.ofSeconds(5) : pollDelay;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        backoff = backoff == null ? new Backoff(null, null) : backoff;
    }

    /**
     * Exponential backoff bounds.
     *
     * @param baseDelay first-retry delay; grows as {@code base * 2^(attempts-1)}
     * @param maxDelay  hard cap on the computed delay
     */
    public record Backoff(Duration baseDelay, Duration maxDelay) {
        public Backoff {
            baseDelay = baseDelay == null ? Duration.ofSeconds(5) : baseDelay;
            maxDelay = maxDelay == null ? Duration.ofMinutes(5) : maxDelay;
        }
    }
}
