package com.forgeops.events.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox message aggregate (DOMAIN_MODEL.md §9, PERSISTENCE_MODEL.md §13): the committed
 * intent to publish an accepted event for asynchronous processing. It is owned by the events
 * module and is created in the <strong>same transaction</strong> as its {@link
 * OperationalEvent} (INV-OUTBOX-001, INV-EVENT-006).
 *
 * <p>It is <em>not</em> authoritative for business facts — it records publish-intent only
 * (INV-OUTBOX-007) — and is never exposed via the API. This slice creates messages in the
 * {@link OutboxStatus#PENDING} state with {@code attempts = 0}; the publisher fields
 * ({@code publishedAt}, {@code nextAttemptAt}, {@code lastError}) exist for a later publisher
 * slice and are {@code null} here.
 *
 * <p>Framework-free by design (ADR-0030): no JPA, Spring, Jackson, PostgreSQL, or broker types.
 *
 * @param id            server-generated UUID v7 identity
 * @param messageType   routing/type of the message
 * @param aggregateType resource kind (e.g. {@code OPERATIONAL_EVENT})
 * @param aggregateId   source resource id (the originating event id)
 * @param payload       canonical JSON message body to publish
 * @param status        lifecycle status
 * @param attempts      retry counter (0 on creation)
 * @param createdAt     creation time (= the event's acceptance time)
 * @param publishedAt   set on successful publication (publisher slice); {@code null} here
 * @param nextAttemptAt earliest next attempt / backoff (publisher slice); {@code null} here
 * @param lastError     last failure detail (publisher slice); {@code null} here
 */
public record OutboxMessage(
        UUID id,
        String messageType,
        String aggregateType,
        UUID aggregateId,
        String payload,
        OutboxStatus status,
        int attempts,
        Instant createdAt,
        Instant publishedAt,
        Instant nextAttemptAt,
        String lastError) {

    public OutboxMessage {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("messageType is required");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }

    /**
     * Factory for a freshly created, not-yet-published outbox message tied to an accepted
     * event: {@link OutboxStatus#PENDING}, zero attempts, and no publisher fields set.
     */
    public static OutboxMessage pending(UUID id,
                                        String messageType,
                                        String aggregateType,
                                        UUID aggregateId,
                                        String payload,
                                        Instant createdAt) {
        return new OutboxMessage(id, messageType, aggregateType, aggregateId, payload,
                OutboxStatus.PENDING, 0, createdAt, null, null, null);
    }
}
