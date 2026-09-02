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

    /**
     * Retention cleanup (Phase 6 Slice 4, PERSISTENCE_MODEL §15, INV-OUTBOX-006): deletes up to
     * {@code batchSize} <strong>already-published</strong> outbox rows whose {@code published_at}
     * is strictly older than {@code cutoff}. The deletion is bounded so a large backlog is
     * pruned in small transactions rather than one long-running, table-locking statement; the
     * caller invokes it repeatedly until it returns {@code 0}.
     *
     * <p>Safety (authoritative): only rows with {@code status = 'PUBLISHED'} <em>and</em> a
     * non-null {@code published_at} strictly before {@code cutoff} are eligible. {@code PENDING}
     * rows — including failed-but-retryable ones ({@code attempts > 0} / {@code next_attempt_at}
     * set) and any row with a {@code NULL published_at} — are <strong>never</strong> deleted
     * (INV-OUTBOX-003). This never affects delivery: the publisher and recovery paths read only
     * {@code PENDING} rows (ADR-0022), so removing old {@code PUBLISHED} rows cannot lose work
     * (INV-OUTBOX-005/006/007). Must run inside a transaction so each batch commits atomically.
     *
     * @param cutoff    exclusive upper bound; a row is eligible only if {@code published_at < cutoff}
     * @param batchSize maximum rows to delete in this call (must be positive)
     * @return the number of rows actually deleted (0 when no eligible rows remain)
     */
    int deletePublishedOlderThan(Instant cutoff, int batchSize);
}
