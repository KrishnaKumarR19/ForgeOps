package com.forgeops.events.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Operational event aggregate root (DOMAIN_MODEL.md §2). An accepted event is durably
 * persisted in PostgreSQL and its core content is immutable after acceptance (INV-EVENT-004);
 * only {@code incidentId} and {@code status} may change during later processing (Phase 6),
 * which this slice does not perform.
 *
 * <p>Three distinct identifiers are never conflated (ADR-0016, DOMAIN_MODEL.md §8):
 * <ul>
 *   <li>{@code id} — the server-generated, globally unique resource identity;</li>
 *   <li>{@code producerEventId} — the source system's own optional id (traceability);</li>
 *   <li>{@code idempotencyKey} — the optional request-idempotency token, unique per
 *       {@code clientId} (the authenticated submitting principal).</li>
 * </ul>
 *
 * <p>The {@code clientId} is always the authenticated principal's user id, never a
 * client-supplied value (SECURITY_DESIGN.md §9, INV-SEC-005). {@code payload} is the
 * canonicalized JSON text and {@code payloadHash} is its deterministic hash (ADR-0025), used
 * to distinguish an idempotent replay from a conflicting reuse of a key.
 *
 * <p>Framework-free by design (ADR-0030): no JPA, Spring, servlet, or JSON-library types.
 */
public final class OperationalEvent {

    private final UUID id;
    private final UUID clientId;
    private final String producerEventId;
    private final String idempotencyKey;
    private final UUID serviceId;
    private final String service;
    private final UUID environmentId;
    private final String environment;
    private final String eventType;
    private final EventSeverity severity;
    private final String failureSignature;
    private final Instant occurredAt;
    private final Instant receivedAt;
    private final String payload;
    private final String payloadHash;
    private final EventStatus status;
    private final UUID incidentId;

    public OperationalEvent(UUID id,
                            UUID clientId,
                            String producerEventId,
                            String idempotencyKey,
                            UUID serviceId,
                            String service,
                            UUID environmentId,
                            String environment,
                            String eventType,
                            EventSeverity severity,
                            String failureSignature,
                            Instant occurredAt,
                            Instant receivedAt,
                            String payload,
                            String payloadHash,
                            EventStatus status,
                            UUID incidentId) {
        this.id = requireNonNull(id, "id");
        this.clientId = requireNonNull(clientId, "clientId");
        this.producerEventId = producerEventId;
        this.idempotencyKey = idempotencyKey;
        this.serviceId = requireNonNull(serviceId, "serviceId");
        this.service = requireText(service, "service");
        this.environmentId = requireNonNull(environmentId, "environmentId");
        this.environment = requireText(environment, "environment");
        this.eventType = requireText(eventType, "eventType");
        this.severity = severity;
        this.failureSignature = failureSignature;
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        this.receivedAt = requireNonNull(receivedAt, "receivedAt");
        this.payload = requireNonNull(payload, "payload");
        this.payloadHash = requireText(payloadHash, "payloadHash");
        this.status = requireNonNull(status, "status");
        this.incidentId = incidentId;
    }

    /**
     * Factory for a freshly accepted event: server-generated id, {@code RECEIVED} status, and
     * not yet correlated to any incident.
     */
    public static OperationalEvent accepted(UUID id,
                                            UUID clientId,
                                            String producerEventId,
                                            String idempotencyKey,
                                            UUID serviceId,
                                            String service,
                                            UUID environmentId,
                                            String environment,
                                            String eventType,
                                            EventSeverity severity,
                                            String failureSignature,
                                            Instant occurredAt,
                                            Instant receivedAt,
                                            String payload,
                                            String payloadHash) {
        return new OperationalEvent(id, clientId, producerEventId, idempotencyKey, serviceId,
                service, environmentId, environment, eventType, severity, failureSignature,
                occurredAt, receivedAt, payload, payloadHash, EventStatus.RECEIVED, null);
    }

    public UUID id() {
        return id;
    }

    public UUID clientId() {
        return clientId;
    }

    public Optional<String> producerEventId() {
        return Optional.ofNullable(producerEventId);
    }

    public Optional<String> idempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String service() {
        return service;
    }

    public UUID environmentId() {
        return environmentId;
    }

    public String environment() {
        return environment;
    }

    public String eventType() {
        return eventType;
    }

    public Optional<EventSeverity> severity() {
        return Optional.ofNullable(severity);
    }

    public Optional<String> failureSignature() {
        return Optional.ofNullable(failureSignature);
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public String payload() {
        return payload;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public EventStatus status() {
        return status;
    }

    public Optional<UUID> incidentId() {
        return Optional.ofNullable(incidentId);
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
