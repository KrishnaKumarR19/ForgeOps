package com.forgeops.incidents.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An append-only assignment-history record (PERSISTENCE_MODEL.md §10, ADR-0021). The incident's
 * <em>current</em> assignee lives on the {@link Incident} aggregate; this records each
 * (re)assignment for history. On reassignment the prior record's {@code unassignedAt} is set and
 * a new record is inserted — all in one transaction (application layer).
 *
 * <p>Framework-free (ADR-0030).
 *
 * @param id           assignment record id (UUID v7)
 * @param incidentId   the incident
 * @param assigneeId   the assigned user
 * @param assignedBy   the actor who performed the assignment
 * @param assignedAt   when assigned
 * @param unassignedAt when superseded/ended, or empty while current
 * @param team         optional team ownership
 */
public record IncidentAssignment(
        UUID id,
        UUID incidentId,
        UUID assigneeId,
        UUID assignedBy,
        Instant assignedAt,
        Instant unassignedAt,
        String team) {

    public IncidentAssignment {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (incidentId == null) {
            throw new IllegalArgumentException("incidentId is required");
        }
        if (assigneeId == null) {
            throw new IllegalArgumentException("assigneeId is required");
        }
        if (assignedBy == null) {
            throw new IllegalArgumentException("assignedBy is required");
        }
        if (assignedAt == null) {
            throw new IllegalArgumentException("assignedAt is required");
        }
    }

    /** Factory for a new, currently-active assignment (not yet superseded). */
    public static IncidentAssignment active(UUID id, UUID incidentId, UUID assigneeId,
                                            UUID assignedBy, Instant assignedAt, String team) {
        return new IncidentAssignment(id, incidentId, assigneeId, assignedBy, assignedAt, null, team);
    }

    public Optional<Instant> unassignedAtValue() {
        return Optional.ofNullable(unassignedAt);
    }

    public Optional<String> teamValue() {
        return Optional.ofNullable(team);
    }
}
