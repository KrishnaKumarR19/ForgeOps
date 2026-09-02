package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.events.domain.OutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for {@link OutboxCleanupService}: the retention cutoff is computed from the
 * injected {@link Clock} minus the configured retention; deletion proceeds in bounded batches
 * until a short batch stops the loop; and the eligibility predicate (only PUBLISHED rows with
 * {@code published_at < cutoff}) is honored. A fixed clock and an in-memory outbox make the
 * behavior deterministic; no database. Synthetic data.
 */
class OutboxCleanupServiceTests {

    private final Instant now = Instant.parse("2026-03-10T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private static final PlatformTransactionManager TX_MANAGER = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }
        public void commit(TransactionStatus s) { }
        public void rollback(TransactionStatus s) { }
    };

    /** In-memory outbox implementing the same eligibility rule as the SQL. */
    private static final class InMemoryOutbox implements OutboxMessageRepository {
        final java.util.Map<UUID, OutboxMessage> byId = new java.util.LinkedHashMap<>();
        final AtomicInteger deleteCalls = new AtomicInteger();
        Instant lastCutoff;

        @Override
        public OutboxMessage save(OutboxMessage m) {
            byId.put(m.id(), m);
            return m;
        }
        @Override
        public List<OutboxMessage> claimPending(int batchSize, Instant nowArg) {
            return List.of();
        }
        @Override
        public void markPublished(UUID id, Instant publishedAt) { }
        @Override
        public void recordFailure(UUID id, int attempts, Instant nextAttemptAt, String lastError) { }

        @Override
        public int deletePublishedOlderThan(Instant cutoff, int batchSize) {
            deleteCalls.incrementAndGet();
            lastCutoff = cutoff;
            List<UUID> eligible = byId.values().stream()
                    .filter(m -> m.status() == OutboxStatus.PUBLISHED)
                    .filter(m -> m.publishedAt() != null && m.publishedAt().isBefore(cutoff))
                    .map(OutboxMessage::id)
                    .limit(batchSize)
                    .toList();
            eligible.forEach(byId::remove);
            return eligible.size();
        }
    }

    private OutboxCleanupProperties props(Duration retention, int batchSize) {
        return new OutboxCleanupProperties(true, retention, Duration.ofHours(1), batchSize);
    }

    private OutboxMessage published(String idSuffix, Instant publishedAt) {
        UUID id = UUID.fromString("018f4000-0000-7000-8000-%012d".formatted(Integer.parseInt(idSuffix)));
        return new OutboxMessage(id, "OPERATIONAL_EVENT_RECEIVED", "OPERATIONAL_EVENT",
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                "{\"event_id\":\"x\"}", OutboxStatus.PUBLISHED, 0,
                publishedAt.minus(Duration.ofMinutes(1)), publishedAt, null, null);
    }

    private OutboxMessage pending(String idSuffix) {
        UUID id = UUID.fromString("018f4000-0000-7000-8000-%012d".formatted(Integer.parseInt(idSuffix)));
        return OutboxMessage.pending(id, "OPERATIONAL_EVENT_RECEIVED", "OPERATIONAL_EVENT",
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                "{\"event_id\":\"x\"}", now.minus(Duration.ofDays(30)));
    }

    @Test
    void cutoffIsNowMinusRetention() {
        InMemoryOutbox outbox = new InMemoryOutbox();
        OutboxCleanupService service = new OutboxCleanupService(
                outbox, props(Duration.ofHours(168), 500), clock, TX_MANAGER);

        service.cleanupOnce();

        // 7-day retention: cutoff is exactly now - 168h.
        assertThat(outbox.lastCutoff).isEqualTo(now.minus(Duration.ofHours(168)));
    }

    @Test
    void deletesOnlyPublishedRowsOlderThanCutoff() {
        InMemoryOutbox outbox = new InMemoryOutbox();
        // Older than 7 days → eligible.
        outbox.save(published("1", now.minus(Duration.ofDays(8))));
        // Inside the window → retained.
        outbox.save(published("2", now.minus(Duration.ofDays(2))));
        // PENDING (even if very old) → never deleted.
        outbox.save(pending("3"));
        OutboxCleanupService service = new OutboxCleanupService(
                outbox, props(Duration.ofHours(168), 500), clock, TX_MANAGER);

        int deleted = service.cleanupOnce();

        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.byId).containsOnlyKeys(
                UUID.fromString("018f4000-0000-7000-8000-000000000002"),
                UUID.fromString("018f4000-0000-7000-8000-000000000003"));
    }

    @Test
    void configuredRetentionOverridesDefault() {
        InMemoryOutbox outbox = new InMemoryOutbox();
        // 1-day retention: a 2-day-old published row becomes eligible.
        outbox.save(published("2", now.minus(Duration.ofDays(2))));
        OutboxCleanupService service = new OutboxCleanupService(
                outbox, props(Duration.ofDays(1), 500), clock, TX_MANAGER);

        int deleted = service.cleanupOnce();

        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.byId).isEmpty();
    }

    @Test
    void processesMultipleBatchesUntilExhausted() {
        InMemoryOutbox outbox = new InMemoryOutbox();
        // 5 eligible rows, batch size 2 → batches of 2,2,1 then stop (last < batchSize).
        for (int i = 1; i <= 5; i++) {
            outbox.save(published(String.valueOf(i), now.minus(Duration.ofDays(10))));
        }
        OutboxCleanupService service = new OutboxCleanupService(
                outbox, props(Duration.ofHours(168), 2), clock, TX_MANAGER);

        int deleted = service.cleanupOnce();

        assertThat(deleted).isEqualTo(5);
        assertThat(outbox.byId).isEmpty();
        // 2 (full) + 2 (full) + 1 (short → stop) = 3 delete calls.
        assertThat(outbox.deleteCalls.get()).isEqualTo(3);
    }

    @Test
    void stopsImmediatelyWhenNothingEligible() {
        InMemoryOutbox outbox = new InMemoryOutbox();
        outbox.save(published("2", now.minus(Duration.ofDays(1)))); // inside window
        OutboxCleanupService service = new OutboxCleanupService(
                outbox, props(Duration.ofHours(168), 500), clock, TX_MANAGER);

        int deleted = service.cleanupOnce();

        assertThat(deleted).isZero();
        assertThat(outbox.deleteCalls.get()).isEqualTo(1); // one empty batch, then stop
        assertThat(outbox.byId).hasSize(1);
    }

    @Test
    void repositoryFailurePropagates() {
        OutboxMessageRepository failing = new OutboxMessageRepository() {
            public OutboxMessage save(OutboxMessage m) {
                throw new UnsupportedOperationException();
            }
            public List<OutboxMessage> claimPending(int batchSize, Instant nowArg) {
                return List.of();
            }
            public void markPublished(UUID id, Instant publishedAt) { }
            public void recordFailure(UUID id, int attempts, Instant nextAttemptAt, String lastError) { }
            public int deletePublishedOlderThan(Instant cutoff, int batchSize) {
                throw new RuntimeException("transient DB failure");
            }
        };
        OutboxCleanupService service = new OutboxCleanupService(
                failing, props(Duration.ofHours(168), 500), clock, TX_MANAGER);

        // The service propagates the failure; the scheduler (tested separately) isolates the
        // cycle so future runs continue.
        assertThatThrownBy(service::cleanupOnce)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("transient");
    }
}
