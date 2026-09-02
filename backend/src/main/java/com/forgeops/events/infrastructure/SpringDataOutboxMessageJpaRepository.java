package com.forgeops.events.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link OutboxMessageEntity}. Package-private: the events
 * module's persistence internals are not visible outside {@code events.infrastructure}
 * (ModuleBoundaryTests). The domain-facing contract is {@link
 * com.forgeops.events.domain.OutboxMessageRepository}.
 */
interface SpringDataOutboxMessageJpaRepository extends JpaRepository<OutboxMessageEntity, UUID> {

    /**
     * Claims due PENDING rows with {@code FOR UPDATE SKIP LOCKED} (ADR-0022, PERSISTENCE_MODEL
     * §14/§16). Uses the partial index {@code (status, next_attempt_at) WHERE status='PENDING'}.
     * Must run inside a transaction; the row locks are held until it commits, so concurrent
     * publishers skip already-claimed rows.
     */
    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessageEntity> claimPending(@Param("batchSize") int batchSize,
                                           @Param("now") Instant now);

    /**
     * Conditional PUBLISHED transition: only affects a row still in {@code PENDING} state, so a
     * stale worker cannot re-mark a row already advanced. Clears retry metadata.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_messages
            SET status = 'PUBLISHED', published_at = :publishedAt,
                next_attempt_at = NULL, last_error = NULL
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    /**
     * Conditional failure record: row stays PENDING (retryable) with incremented attempts,
     * a backoff {@code next_attempt_at}, and a bounded {@code last_error}.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_messages
            SET attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :lastError
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int recordFailure(@Param("id") UUID id, @Param("attempts") int attempts,
                      @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("lastError") String lastError);

    /**
     * Retention cleanup (Phase 6 Slice 4, PERSISTENCE_MODEL §15, INV-OUTBOX-006): bounded delete
     * of already-PUBLISHED rows older than {@code cutoff}. The inner {@code SELECT ... ORDER BY
     * published_at LIMIT :batchSize} uses the partial index {@code (published_at) WHERE
     * status='PUBLISHED'} and caps each statement so a large backlog is pruned in small,
     * committable batches. The {@code status='PUBLISHED' AND published_at < :cutoff} predicate
     * (a {@code NULL published_at} fails {@code < :cutoff}) guarantees no PENDING/retryable/
     * unpublished row is ever removed (INV-OUTBOX-003). Must run inside a transaction.
     *
     * @return the number of rows deleted by this call
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox_messages
            WHERE id IN (
                SELECT id FROM outbox_messages
                WHERE status = 'PUBLISHED' AND published_at < :cutoff
                ORDER BY published_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deletePublishedOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
