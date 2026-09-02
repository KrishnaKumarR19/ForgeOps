package com.forgeops.events.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for outbox retention cleanup (Phase 6 Slice 4, PERSISTENCE_MODEL §15). Old
 * {@code PUBLISHED} rows are pruned after a bounded retention window (INV-OUTBOX-006); the
 * duration, cadence, and batch size are configuration decisions (PERSISTENCE_MODEL §15 —
 * "configurable, not hardcoded"). ForgeOps v1 defaults: 7-day retention, hourly cadence,
 * 500 rows per batch.
 *
 * <p>Safe, non-secret defaults; overridable per environment. Invalid values (non-positive
 * retention, batch size, or delay) fall back to the deterministic defaults rather than being
 * accepted. {@code enabled} defaults to {@code true} so cleanup runs in production; tests set
 * it to {@code false} to keep the timer from racing assertions.
 *
 * @param enabled    whether the cleanup scheduler is active (true in production)
 * @param retention  how long a PUBLISHED row is retained before it is eligible for deletion
 * @param fixedDelay fixed delay between cleanup cycles
 * @param batchSize  maximum rows deleted per batch (each batch commits independently)
 */
@ConfigurationProperties(prefix = "forgeops.outbox.cleanup")
public record OutboxCleanupProperties(
        Boolean enabled,
        Duration retention,
        Duration fixedDelay,
        Integer batchSize) {

    /** ForgeOps v1 defaults (documented in PERSISTENCE_MODEL §15). */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(168); // 7 days
    private static final Duration DEFAULT_FIXED_DELAY = Duration.ofHours(1);
    private static final int DEFAULT_BATCH_SIZE = 500;

    public OutboxCleanupProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        retention = (retention == null || retention.isZero() || retention.isNegative())
                ? DEFAULT_RETENTION : retention;
        fixedDelay = (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative())
                ? DEFAULT_FIXED_DELAY : fixedDelay;
        batchSize = (batchSize == null || batchSize <= 0) ? DEFAULT_BATCH_SIZE : batchSize;
    }
}
