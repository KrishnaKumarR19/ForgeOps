package com.forgeops.audit.infrastructure;

import com.forgeops.audit.domain.AuditActorType;
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
 * JPA persistence entity for an append-only audit entry, kept separate from the {@link
 * com.forgeops.audit.domain.AuditEntry} domain value (ADR-0035). Mapped to the {@code
 * audit_entries} table ({@code V5__audit.sql}); {@code ddl-auto=validate} checks the mapping.
 * All columns are {@code updatable = false}: entries are insert-only (INV-INC-008); nothing
 * ever updates a persisted row. {@code old_value}/{@code new_value} are PostgreSQL {@code jsonb}
 * via {@link JdbcTypeCode}({@link SqlTypes#JSON}) over canonical JSON text.
 *
 * <p>Package-private: audit persistence internals are not visible outside {@code
 * audit.infrastructure} (ADR-0030).
 */
@Entity
@Table(name = "audit_entries")
class AuditEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private AuditActorType actorType;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    protected AuditEntryEntity() {
        // Required by JPA.
    }

    AuditEntryEntity(UUID id, UUID actorId, AuditActorType actorType, String action,
                     String resourceType, UUID resourceId, Instant occurredAt, String oldValue,
                     String newValue, String correlationId) {
        this.id = id;
        this.actorId = actorId;
        this.actorType = actorType;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.occurredAt = occurredAt;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.correlationId = correlationId;
    }

    UUID getId() {
        return id;
    }

    UUID getActorId() {
        return actorId;
    }

    AuditActorType getActorType() {
        return actorType;
    }

    String getAction() {
        return action;
    }

    String getResourceType() {
        return resourceType;
    }

    UUID getResourceId() {
        return resourceId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    String getOldValue() {
        return oldValue;
    }

    String getNewValue() {
        return newValue;
    }

    String getCorrelationId() {
        return correlationId;
    }
}
