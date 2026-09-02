package com.forgeops.events.infrastructure;

import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link OutboxMessageRepository} port (ADR-0030).
 * Maps between the framework-free {@link OutboxMessage} aggregate and {@link
 * OutboxMessageEntity}. {@code saveAndFlush} forces the INSERT to reach PostgreSQL within the
 * current transaction so the write participates in the event-acceptance transaction's commit
 * or rollback (INV-OUTBOX-001).
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
}
