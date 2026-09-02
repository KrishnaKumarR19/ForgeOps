package com.forgeops.events.infrastructure;

import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link OutboxMessageRepository} port (ADR-0030).
 * Maps between the framework-free {@link OutboxMessage} aggregate and {@link
 * OutboxMessageEntity}, keeping JPA out of the domain.
 *
 * <p>{@code save} uses {@code saveAndFlush} so the INSERT participates in the event-acceptance
 * transaction (INV-OUTBOX-001). {@code claimPending}/{@code markPublished}/{@code recordFailure}
 * delegate to native queries and run inside the publisher's transaction (ADR-0022); the
 * conditional {@code WHERE status='PENDING'} updates prevent a stale worker from re-marking a
 * row.
 */
@Repository
class JpaOutboxMessageRepository implements OutboxMessageRepository {

    private final SpringDataOutboxMessageJpaRepository jpa;

    JpaOutboxMessageRepository(SpringDataOutboxMessageJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public OutboxMessage save(OutboxMessage message) {
        jpa.saveAndFlush(toEntity(message));
        return message;
    }

    @Override
    public List<OutboxMessage> claimPending(int batchSize, Instant now) {
        return jpa.claimPending(batchSize, now).stream()
                .map(JpaOutboxMessageRepository::toDomain)
                .toList();
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        jpa.markPublished(id, publishedAt);
    }

    @Override
    public void recordFailure(UUID id, int attempts, Instant nextAttemptAt, String lastError) {
        jpa.recordFailure(id, attempts, nextAttemptAt, lastError);
    }

    private static OutboxMessageEntity toEntity(OutboxMessage m) {
        return new OutboxMessageEntity(
                m.id(),
                m.messageType(),
                m.aggregateType(),
                m.aggregateId(),
                m.payload(),
                m.status(),
                m.attempts(),
                m.createdAt(),
                m.publishedAt(),
                m.nextAttemptAt(),
                m.lastError());
    }

    private static OutboxMessage toDomain(OutboxMessageEntity e) {
        return new OutboxMessage(
                e.getId(),
                e.getMessageType(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getPayload(),
                e.getStatus(),
                e.getAttempts(),
                e.getCreatedAt(),
                e.getPublishedAt(),
                e.getNextAttemptAt(),
                e.getLastError());
    }
}
