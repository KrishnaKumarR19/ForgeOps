package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.events.application.OutboxCleanupService;
import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.events.domain.OutboxStatus;
import com.forgeops.testsupport.PostgresTestContainer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox retention cleanup integration tests against real PostgreSQL (Testcontainers), Phase 6
 * Slice 4 (PERSISTENCE_MODEL §15, INV-OUTBOX-003/006). Proves the authoritative eligibility
 * rule and bounded batching against the actual {@code outbox_messages} table and its
 * {@code (published_at) WHERE status='PUBLISHED'} partial index.
 *
 * <p>Cleanup is driven directly via {@link OutboxMessageRepository#deletePublishedOlderThan}
 * (with a 7-day cutoff computed in the test) and via {@link OutboxCleanupService}; the
 * background scheduler is disabled in tests. The {@code @Modifying} delete runs inside an
 * explicit {@link TransactionTemplate} (Slice 2 lesson); temporal params are bound as
 * {@link Timestamp} (Slice 3 lesson). DB isolated via TRUNCATE; bootstrap admin disabled.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class OutboxRetentionCleanupIntegrationTests {

    @Autowired
    private OutboxMessageRepository outbox;
    @Autowired
    private OutboxCleanupService cleanupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Instant now = Instant.parse("2026-03-10T00:00:00Z");
    private final Instant cutoff = now.minus(Duration.ofHours(168)); // 7-day retention window

    private static final UUID AGGREGATE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000e1");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_messages CASCADE");
    }

    // ----- helpers -------------------------------------------------------------

    private UUID id(String suffix) {
        return UUID.fromString("018f4000-0000-7000-8000-%012d".formatted(Integer.parseInt(suffix)));
    }

    private void insertPublished(UUID id, Instant publishedAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox_messages
                  (id, message_type, aggregate_type, aggregate_id, payload, status, attempts,
                   created_at, published_at, next_attempt_at, last_error)
                VALUES (?::uuid, 'OPERATIONAL_EVENT_RECEIVED', 'OPERATIONAL_EVENT', ?::uuid,
                        ?::jsonb, 'PUBLISHED', 0, ?, ?, NULL, NULL)
                """,
                id.toString(), AGGREGATE_ID.toString(), "{\"event_id\":\"" + id + "\"}",
                Timestamp.from(publishedAt.minus(Duration.ofMinutes(1))), Timestamp.from(publishedAt));
    }

    /** A PUBLISHED row that (erroneously) has a NULL published_at — must never be deleted. */
    private void insertPublishedWithNullPublishedAt(UUID id) {
        jdbcTemplate.update("""
                INSERT INTO outbox_messages
                  (id, message_type, aggregate_type, aggregate_id, payload, status, attempts,
                   created_at, published_at, next_attempt_at, last_error)
                VALUES (?::uuid, 'OPERATIONAL_EVENT_RECEIVED', 'OPERATIONAL_EVENT', ?::uuid,
                        ?::jsonb, 'PUBLISHED', 0, ?, NULL, NULL, NULL)
                """,
                id.toString(), AGGREGATE_ID.toString(), "{\"event_id\":\"" + id + "\"}",
                Timestamp.from(now.minus(Duration.ofDays(30))));
    }

    private void insertPending(UUID id, int attempts, Instant nextAttemptAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox_messages
                  (id, message_type, aggregate_type, aggregate_id, payload, status, attempts,
                   created_at, published_at, next_attempt_at, last_error)
                VALUES (?::uuid, 'OPERATIONAL_EVENT_RECEIVED', 'OPERATIONAL_EVENT', ?::uuid,
                        ?::jsonb, 'PENDING', ?, ?, NULL, ?, ?)
                """,
                id.toString(), AGGREGATE_ID.toString(), "{\"event_id\":\"" + id + "\"}",
                attempts, Timestamp.from(now.minus(Duration.ofDays(30))),
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                attempts > 0 ? "prev failure" : null);
    }

    private boolean exists(UUID id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE id = ?::uuid", Long.class, id.toString());
        return count != null && count > 0;
    }

    private int deleteBatchInTx(int batchSize) {
        Integer deleted = new TransactionTemplate(transactionManager)
                .execute(status -> outbox.deletePublishedOlderThan(cutoff, batchSize));
        return deleted == null ? 0 : deleted;
    }

    // ----- tests ---------------------------------------------------------------

    @Test
    void deletesOldPublishedRow() {
        UUID old = id("1");
        insertPublished(old, now.minus(Duration.ofDays(8)));

        int deleted = deleteBatchInTx(500);

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(old)).isFalse();
    }

    @Test
    void retainsPublishedRowExactlyAtBoundary() {
        // published_at == cutoff is NOT < cutoff, so it is retained (exclusive bound).
        UUID boundary = id("2");
        insertPublished(boundary, cutoff);

        int deleted = deleteBatchInTx(500);

        assertThat(deleted).isZero();
        assertThat(exists(boundary)).isTrue();
    }

    @Test
    void retainsRecentPublishedRow() {
        UUID recent = id("3");
        insertPublished(recent, now.minus(Duration.ofDays(1)));

        assertThat(deleteBatchInTx(500)).isZero();
        assertThat(exists(recent)).isTrue();
    }

    @Test
    void retainsPendingRows() {
        UUID freshPending = id("4");
        UUID retryablePending = id("5");     // attempts > 0
        UUID scheduledPending = id("6");     // next_attempt_at set
        insertPending(freshPending, 0, null);
        insertPending(retryablePending, 3, null);
        insertPending(scheduledPending, 1, now.plus(Duration.ofMinutes(5)));

        assertThat(deleteBatchInTx(500)).isZero();
        assertThat(exists(freshPending)).isTrue();
        assertThat(exists(retryablePending)).isTrue();
        assertThat(exists(scheduledPending)).isTrue();
    }

    @Test
    void neverDeletesPublishedRowWithNullPublishedAt() {
        UUID weird = id("7");
        insertPublishedWithNullPublishedAt(weird);

        // NULL published_at fails the `published_at < cutoff` predicate → retained.
        assertThat(deleteBatchInTx(500)).isZero();
        assertThat(exists(weird)).isTrue();
    }

    @Test
    void deletesOnlyEligibleAmongMixedRows() {
        UUID eligible = id("8");
        UUID recent = id("9");
        UUID pending = id("10");
        insertPublished(eligible, now.minus(Duration.ofDays(9)));
        insertPublished(recent, now.minus(Duration.ofDays(1)));
        insertPending(pending, 0, null);

        int deleted = deleteBatchInTx(500);

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(eligible)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(pending)).isTrue();
    }

    @Test
    void handlesMoreThanOneBatchViaService() {
        // 1200 eligible old-published rows; service default batch (500) → 3 batches (500,500,200).
        for (int i = 1; i <= 1200; i++) {
            insertPublished(id(String.valueOf(1000 + i)), now.minus(Duration.ofDays(10)));
        }
        // Also a couple of retained rows to prove they survive.
        insertPublished(id("1"), now.minus(Duration.ofDays(1)));
        insertPending(id("2"), 0, null);

        int deleted = cleanupService.cleanupOnce();

        assertThat(deleted).isEqualTo(1200);
        Long remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_messages", Long.class);
        assertThat(remaining).isEqualTo(2L); // the recent published + the pending
    }

    @Test
    void repeatedCleanupIsNoOpOnceRowsAreRemoved() {
        insertPublished(id("11"), now.minus(Duration.ofDays(8)));

        assertThat(deleteBatchInTx(500)).isEqualTo(1);
        // Second and third passes have nothing eligible → no-op, no error.
        assertThat(deleteBatchInTx(500)).isZero();
        assertThat(cleanupService.cleanupOnce()).isZero();
    }

    @Test
    void failedDeleteTransactionDoesNotCorruptRemainingRows() {
        UUID eligible = id("12");
        UUID recent = id("13");
        insertPublished(eligible, now.minus(Duration.ofDays(8)));
        insertPublished(recent, now.minus(Duration.ofDays(1)));

        // Force a rollback around a delete: the batch deletes the eligible row, then we throw,
        // so the whole transaction rolls back and NOTHING is lost/changed.
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                outbox.deletePublishedOlderThan(cutoff, 500);
                throw new RuntimeException("simulated failure mid-cleanup");
            });
        } catch (RuntimeException expected) {
            // ignored
        }

        // Rolled back: the eligible row is still present, the recent row untouched.
        assertThat(exists(eligible)).isTrue();
        assertThat(exists(recent)).isTrue();

        // A subsequent clean run still works and removes exactly the eligible row.
        assertThat(deleteBatchInTx(500)).isEqualTo(1);
        assertThat(exists(eligible)).isFalse();
        assertThat(exists(recent)).isTrue();
    }

    @Test
    void cleanupDoesNotTouchPendingRowsAPublisherWouldClaim() {
        // Disjoint row sets: an old PUBLISHED row (cleanup-eligible) and an old PENDING row
        // (publisher-claimable). Cleanup removes only the PUBLISHED one; the PENDING row — the
        // one the publisher would claim — is left intact (INV-OUTBOX-003).
        UUID publishedOld = id("14");
        UUID pendingOld = id("15");
        insertPublished(publishedOld, now.minus(Duration.ofDays(10)));
        insertPending(pendingOld, 0, null);

        int deleted = cleanupService.cleanupOnce();

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(publishedOld)).isFalse();
        assertThat(exists(pendingOld)).isTrue();

        // The still-present PENDING row remains claimable by the publisher path.
        List<OutboxMessage> claimable = new TransactionTemplate(transactionManager)
                .execute(status -> outbox.claimPending(10, now));
        assertThat(claimable).extracting(OutboxMessage::id).contains(pendingOld);
        assertThat(claimable).extracting(OutboxMessage::status)
                .allMatch(s -> s == OutboxStatus.PENDING);
    }
}
