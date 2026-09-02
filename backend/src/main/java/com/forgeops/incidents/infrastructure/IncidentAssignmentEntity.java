package com.forgeops.incidents.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for an append-only assignment-history row (PERSISTENCE_MODEL.md §10), separate from
 * the {@link com.forgeops.incidents.domain.IncidentAssignment} domain record (ADR-0035). Mapped
 * to {@code incident_assignments} ({@code V6}). {@code unassigned_at} is the only mutable column
 * (set once when the record is superseded); all others are insert-only. Package-private.
 */
@Entity
@Table(name = "incident_assignments")
class IncidentAssignmentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "assignee_id", nullable = false, updatable = false)
    private UUID assigneeId;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "unassigned_at")
    private Instant unassignedAt;

    @Column(name = "team", updatable = false)
    private String team;

    protected IncidentAssignmentEntity() {
        // Required by JPA.
    }

    IncidentAssignmentEntity(UUID id, UUID incidentId, UUID assigneeId, UUID assignedBy,
                             Instant assignedAt, Instant unassignedAt, String team) {
        this.id = id;
        this.incidentId = incidentId;
        this.assigneeId = assigneeId;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
        this.unassignedAt = unassignedAt;
        this.team = team;
    }

    UUID getId() {
        return id;
    }

    UUID getIncidentId() {
        return incidentId;
    }

    UUID getAssigneeId() {
        return assigneeId;
    }

    UUID getAssignedBy() {
        return assignedBy;
    }

    Instant getAssignedAt() {
        return assignedAt;
    }

    Instant getUnassignedAt() {
        return unassignedAt;
    }

    String getTeam() {
        return team;
    }
}
