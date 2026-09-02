package com.forgeops.events.infrastructure;

import com.forgeops.events.domain.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence entity for an outbox message, kept separate from the {@link
 * com.forgeops.events.domain.OutboxMessage} domain aggregate (ADR-0035). Mapped to the
 * {@code outbox_messages} table created by the Flyway migration; {@code ddl-auto=validate}
 * ensures this mapping matches it. The {@code payload} is stored as PostgreSQL {@code jsonb}
 * via {@link JdbcTypeCode}({@link SqlTypes#JSON}) over canonical JSON text.
 */
@Entity
@Table(name = "outbox_messages")
class OutboxMessageEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "message_type", nullable = false, updatable = false)
    private String messageType;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxMessageEntity() {
        // Required by JPA.
    }

    OutboxMessageEntity(UUID id, String messageType, String aggregateType, UUID aggregateId,
                        String payload, OutboxStatus status, int attempts, Instant createdAt,
                        Instant publishedAt, Instant nextAttemptAt, String lastError) {
        this.id = id;
        this.messageType = messageType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
    }

    UUID getId() {
        return id;
    }

    String getMessageType() {
        return messageType;
    }

    String getAggregateType() {
        return aggregateType;
    }

    UUID getAggregateId() {
        return aggregateId;
    }

    String getPayload() {
        return payload;
    }

    OutboxStatus getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    String getLastError() {
        return lastError;
    }
}
