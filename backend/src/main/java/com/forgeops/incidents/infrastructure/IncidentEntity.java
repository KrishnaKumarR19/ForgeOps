package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.IncidentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for an incident, kept separate from the {@link
 * com.forgeops.incidents.domain.Incident} domain aggregate (ADR-0035) so JPA concerns do not
 * leak into the framework-free domain. Mapped to the {@code incidents} table created by
 * {@code V4__incidents.sql}; {@code ddl-auto=validate} ensures this mapping matches it.
 *
 * <p>Package-private: the incidents module's persistence internals are not visible outside
 * {@code incidents.infrastructure} (ADR-0030, ModuleBoundaryTests).
 *
 * <p>{@code version} is mapped as a plain column (not JPA {@code @Version}) in this slice: the
 * foundation only persists/round-trips the value. Command-side optimistic-lock behavior
 * (ETag/If-Match, version increment on transition) belongs to a later slice.
 */
@Entity
@Table(name = "incidents")
class IncidentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "title")
    private String title;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "failure_signature")
    private String failureSignature;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private IncidentState state;

    @Column(name = "current_assignee_id")
    private UUID currentAssigneeId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected IncidentEntity() {
        // Required by JPA.
    }

    IncidentEntity(UUID id, String title, UUID serviceId, UUID environmentId,
                   String failureSignature, IncidentSeverity severity, IncidentState state,
                   UUID currentAssigneeId, long version, Instant createdAt, Instant resolvedAt,
                   Instant closedAt) {
        this.id = id;
        this.title = title;
        this.serviceId = serviceId;
        this.environmentId = environmentId;
        this.failureSignature = failureSignature;
        this.severity = severity;
        this.state = state;
        this.currentAssigneeId = currentAssigneeId;
        this.version = version;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
        this.closedAt = closedAt;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    UUID getServiceId() {
        return serviceId;
    }

    UUID getEnvironmentId() {
        return environmentId;
    }

    String getFailureSignature() {
        return failureSignature;
    }

    IncidentSeverity getSeverity() {
        return severity;
    }

    IncidentState getState() {
        return state;
    }

    UUID getCurrentAssigneeId() {
        return currentAssigneeId;
    }

    long getVersion() {
        return version;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getResolvedAt() {
        return resolvedAt;
    }

    Instant getClosedAt() {
        return closedAt;
    }
}
