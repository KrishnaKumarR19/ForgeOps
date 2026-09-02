package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.events.domain.MessageBroker;
import com.forgeops.events.domain.MessagePublishException;
import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.events.domain.OutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for {@link OutboxPublishService}: success → PUBLISHED (published_at set, retry
 * metadata cleared, attempts unchanged); failure → PENDING with attempts+1, backoff
 * next_attempt_at, bounded last_error; a single failure does not abort the rest of the batch.
 * Uses in-memory fakes and a fixed clock; no database or broker. Synthetic data.
 */
class OutboxPublishServiceTests {

    private final Instant now = Instant.parse("2026-03-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    /** In-memory outbox capturing claim/mark/fail effects. */
    private static final class InMemoryOutbox implements OutboxMessageRepository {
        final Map<UUID, OutboxMessage> byId = new LinkedHashMap<>();

        @Override
        public OutboxMessage save(OutboxMessage m) {
            byId.put(m.id(), m);
            return m;
        }

        @Override
        public List<OutboxMessage> claimPending(int batchSize, Instant nowArg) {
            List<OutboxMessage> due = new ArrayList<>();
            for (OutboxMessage m : byId.values()) {
                boolean pending = m.status() == OutboxStatus.PENDING;
                boolean dueNow = m.nextAttemptAt() == null || !m.nextAttemptAt().isAfter(nowArg);
                if (pending && dueNow && due.size() < batchSize) {
                    due.add(m);
                }
            }
            return due;
        }

        @Override
        public void markPublished(UUID id, Instant publishedAt) {
            OutboxMessage m = byId.get(id);
            if (m != null && m.status() == OutboxStatus.PENDING) {
                byId.put(id, new OutboxMessage(m.id(), m.messageType(), m.aggregateType(),
                        m.aggregateId(), m.payload(), OutboxStatus.PUBLISHED, m.attempts(),
                        m.createdAt(), publishedAt, null, null));
            }
        }

        @Override
        public void recordFailure(UUID id, int attempts, Instant nextAttemptAt, String lastError) {
            OutboxMessage m = byId.get(id);
            if (m != null && m.status() == OutboxStatus.PENDING) {
                byId.put(id, new OutboxMessage(m.id(), m.messageType(), m.aggregateType(),
                        m.aggregateId(), m.payload(), OutboxStatus.PENDING, attempts,
                        m.createdAt(), null, nextAttemptAt, lastError));
            }
        }
    }

    /** Broker that fails for a configured set of message ids. */
    private static final class FakeBroker implements MessageBroker {
        final List<UUID> published = new ArrayList<>();
        java.util.Set<UUID> failIds = java.util.Set.of();

        @Override
        public void publish(OutboxMessage message) {
            if (failIds.contains(message.id())) {
                throw new MessagePublishException("broker unavailable for " + message.id());
            }
            published.add(message.id());
        }
    }

    private static final PlatformTransactionManager TX_MANAGER = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }
        public void commit(TransactionStatus s) { }
        public void rollback(TransactionStatus s) { }
    };

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final FakeBroker broker = new FakeBroker();
    private final OutboxPublisherProperties props = new OutboxPublisherProperties(
            Duration.ofSeconds(5), 100,
            new OutboxPublisherProperties.Backoff(Duration.ofSeconds(5), Duration.ofMinutes(5)));
    private final BackoffPolicy backoff = new BackoffPolicy(props);
    private final OutboxPublishService service = new OutboxPublishService(
            outbox, broker, backoff, props, clock, TX_MANAGER);

    private OutboxMessage pending(String idSuffix) {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-%012d".formatted(Integer.parseInt(idSuffix)));
        return OutboxMessage.pending(id, "OPERATIONAL_EVENT_RECEIVED", "OPERATIONAL_EVENT",
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                "{\"event_id\":\"x\"}", now.minusSeconds(1));
    }

    @Test
    void publishesPendingMessageAndMarksPublished() {
        OutboxMessage m = pending("1");
        outbox.save(m);

        int published = service.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(broker.published).containsExactly(m.id());
        OutboxMessage after = outbox.byId.get(m.id());
        assertThat(after.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(after.publishedAt()).isEqualTo(now);
        assertThat(after.nextAttemptAt()).isNull();
        assertThat(after.lastError()).isNull();
        assertThat(after.attempts()).isZero(); // success does not increment attempts
    }

    @Test
    void failedPublishLeavesMessagePendingWithRetryMetadata() {
        OutboxMessage m = pending("1");
        outbox.save(m);
        broker.failIds = java.util.Set.of(m.id());

        int published = service.publishBatch();

        assertThat(published).isZero();
        OutboxMessage after = outbox.byId.get(m.id());
        assertThat(after.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.nextAttemptAt()).isEqualTo(now.plus(Duration.ofSeconds(5)));
        assertThat(after.lastError()).isNotBlank().contains(m.id().toString());
        assertThat(after.publishedAt()).isNull();
    }

    @Test
    void backoffGrowsWithPriorAttempts() {
        // A message that has already failed twice; next failure is attempt 3 → 20s.
        OutboxMessage m = pending("1");
        OutboxMessage triedTwice = new OutboxMessage(m.id(), m.messageType(), m.aggregateType(),
                m.aggregateId(), m.payload(), OutboxStatus.PENDING, 2, m.createdAt(),
                null, now.minusSeconds(1), "prev");
        outbox.save(triedTwice);
        broker.failIds = java.util.Set.of(m.id());

        service.publishBatch();

        OutboxMessage after = outbox.byId.get(m.id());
        assertThat(after.attempts()).isEqualTo(3);
        assertThat(after.nextAttemptAt()).isEqualTo(now.plus(Duration.ofSeconds(20)));
    }

    @Test
    void oneFailureDoesNotAbortTheRestOfTheBatch() {
        OutboxMessage ok1 = pending("1");
        OutboxMessage bad = pending("2");
        OutboxMessage ok2 = pending("3");
        outbox.save(ok1);
        outbox.save(bad);
        outbox.save(ok2);
        broker.failIds = java.util.Set.of(bad.id());

        int published = service.publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(outbox.byId.get(ok1.id()).status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.byId.get(ok2.id()).status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.byId.get(bad.id()).status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.byId.get(bad.id()).attempts()).isEqualTo(1);
    }

    @Test
    void doesNotClaimMessagesWhoseRetryIsInTheFuture() {
        OutboxMessage m = pending("1");
        OutboxMessage future = new OutboxMessage(m.id(), m.messageType(), m.aggregateType(),
                m.aggregateId(), m.payload(), OutboxStatus.PENDING, 1, m.createdAt(),
                null, now.plusSeconds(60), "prev");
        outbox.save(future);

        int published = service.publishBatch();

        assertThat(published).isZero();
        assertThat(broker.published).isEmpty();
        assertThat(outbox.byId.get(m.id()).status()).isEqualTo(OutboxStatus.PENDING);
    }
}
