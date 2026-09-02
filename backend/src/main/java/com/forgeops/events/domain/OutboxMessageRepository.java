package com.forgeops.events.domain;

/**
 * Domain port for persisting outbox messages (ADR-0030). The infrastructure adapter writes to
 * PostgreSQL. This slice needs only {@code save}: the message is persisted in the same
 * transaction as its operational event (INV-OUTBOX-001). Reading/claiming/publishing outbox
 * records is a later publisher slice and is deliberately not part of this port yet.
 */
public interface OutboxMessageRepository {

    /** Persists a new outbox message. Must run within the event-acceptance transaction. */
    OutboxMessage save(OutboxMessage message);
}
