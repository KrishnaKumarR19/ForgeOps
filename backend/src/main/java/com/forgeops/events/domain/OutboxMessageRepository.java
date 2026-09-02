package com.forgeops.events.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain port for the outbox store (ADR-0030). PostgreSQL is authoritative. Slice 1 needed
 * only {@code save} (the message is written in the same transaction as its operational event,
 * INV-OUTBOX-001). Slice 2 (the publisher) adds claiming and status-update operations.
 *
 * <p>All methods are framework-free (no JPA/Spring/RabbitMQ types). The claim/update calls are
 * intended to run inside the publisher's transaction so that claiming a batch with row locks
 * (PostgreSQL {@code FOR UPDATE SKIP LOCKED}, ADR-0022) and marking the result are atomic.
 */
public interface OutboxMessageRepository {

    /** Persists a new outbox message. Must run within the event-acceptance transaction. */
    OutboxMessage save(OutboxMessage message);

    /**
     * Claims up to {@code batchSize} due {@code PENDING} messages for publication, locking the
     * rows with {@code FOR UPDATE SKIP LOCKED} so concurrent publishers never claim the same
     * row (ADR-0022, PERSISTENCE_MODEL §14). "Due" means {@code next_attempt_at IS NULL} (never
     * tried) or {@code next_attempt_at <= now} (retry due). Ordered by {@code created_at}.
     * Must be called inside the publisher transaction; the locks are held until it commits.
     */
    List<OutboxMessage> claimPending(int batchSize, Instant now);

    /**
     * Marks a message {@code PUBLISHED} after confirmed broker acceptance: sets
     * {@code published_at}, clears {@code next_attempt_at} and {@code last_error}. The update is
     * conditional on the row still being {@code PENDING} so a stale worker cannot clobber it.
     */
    void markPublished(UUID id, Instant publishedAt);

    /**
     * Records a failed publication: the row stays {@code PENDING} (retryable, INV-OUTBOX-003)
     * with an incremented {@code attempts}, a {@code next_attempt_at} backoff time, and a
     * bounded {@code last_error}. Conditional on the row still being {@code PENDING}.
     */
    void recordFailure(UUID id, int attempts, Instant nextAttemptAt, String lastError);
}
