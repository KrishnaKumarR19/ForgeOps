package com.forgeops.events.infrastructure;

import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.EventStatus;
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
 * JPA persistence entity for an operational event, kept separate from the {@link
 * com.forgeops.events.domain.OperationalEvent} domain aggregate (ADR-0035) so Hibernate/JPA
 * concerns do not leak into the framework-free domain. Mapped to the {@code
 * operational_events} table created by the Flyway migration; {@code ddl-auto=validate}
 * ensures this mapping matches it.
 *
 * <p>The {@code payload} is stored as PostgreSQL {@code jsonb} via
 * {@link JdbcTypeCode}({@link SqlTypes#JSON}) over the canonical JSON text — no external JSON
 * mapping dependency is required.
 */
@Entity
@Table(name = "operational_events")
class OperationalEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "producer_event_id", updatable = false)
    private String producerEventId;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", updatable = false)
    private EventSeverity severity;

    @Column(name = "failure_signature", updatable = false)
    private String failureSignature;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "payload_hash", nullable = false, updatable = false)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EventStatus status;

    /** 0..1 owning incident; nullable and (in this slice) always null on acceptance. */
    @Column(name = "incident_id")
    private UUID incidentId;

    protected OperationalEventEntity() {
        // Required by JPA.
    }

    OperationalEventEntity(UUID id, UUID clientId, String producerEventId, String idempotencyKey,
                           UUID serviceId, UUID environmentId, String eventType,
                           EventSeverity severity, String failureSignature, Instant occurredAt,
                           Instant receivedAt, String payload, String payloadHash,
                           EventStatus status, UUID incidentId) {
        this.id = id;
        this.clientId = clientId;
        this.producerEventId = producerEventId;
        this.idempotencyKey = idempotencyKey;
        this.serviceId = serviceId;
        this.environmentId = environmentId;
        this.eventType = eventType;
        this.severity = severity;
        this.failureSignature = failureSignature;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.payload = payload;
        this.payloadHash = payloadHash;
        this.status = status;
        this.incidentId = incidentId;
    }

    UUID getId() {
        return id;
    }

    UUID getClientId() {
        return clientId;
    }

    String getProducerEventId() {
        return producerEventId;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    UUID getServiceId() {
        return serviceId;
    }

    UUID getEnvironmentId() {
        return environmentId;
    }

    String getEventType() {
        return eventType;
    }

    EventSeverity getSeverity() {
        return severity;
    }

    String getFailureSignature() {
        return failureSignature;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }

    String getPayload() {
        return payload;
    }

    String getPayloadHash() {
        return payloadHash;
    }

    EventStatus getStatus() {
        return status;
    }

    UUID getIncidentId() {
        return incidentId;
    }
}
