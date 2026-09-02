package com.forgeops.events.domain;

/**
 * Result of an idempotent attempt to mark an operational event {@code PROCESSED}
 * (INV-MSG-003, FR-RL-3/10). Returned by
 * {@link OperationalEventRepository#markProcessed(java.util.UUID)} so the asynchronous
 * consumer can distinguish the three outcomes of an at-least-once delivery without a
 * separate check-then-update (which would be racy):
 *
 * <ul>
 *   <li>{@link #MARKED} — this delivery transitioned the row {@code RECEIVED → PROCESSED};
 *       the effect was applied exactly once.</li>
 *   <li>{@link #ALREADY_PROCESSED} — a duplicate/redelivered message; the row was already
 *       {@code PROCESSED}, so no additional effect occurred (the idempotent no-op).</li>
 *   <li>{@link #NOT_FOUND} — no event exists for the id. Treated as a non-retryable,
 *       poison message by the consumer rather than retried forever.</li>
 * </ul>
 *
 * <p>Framework-free (ADR-0030): a plain domain enum with no JPA/Spring/RabbitMQ types.
 */
public enum ProcessingOutcome {
    MARKED,
    ALREADY_PROCESSED,
    NOT_FOUND
}
