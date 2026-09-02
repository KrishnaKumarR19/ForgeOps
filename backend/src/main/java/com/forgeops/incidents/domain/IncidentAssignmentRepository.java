package com.forgeops.incidents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain port for the append-only assignment history (ADR-0021, ADR-0030, PERSISTENCE_MODEL.md
 * §10). PostgreSQL is authoritative. Historical records are never edited or deleted; a
 * reassignment closes the current record ({@code unassignedAt}) and inserts a new one, in the
 * caller's transaction. Framework-free — no JPA/Spring/SQL types.
 */
public interface IncidentAssignmentRepository {

    /** Appends a new assignment-history record. */
    IncidentAssignment save(IncidentAssignment assignment);

    /**
     * Closes the currently-active assignment(s) for an incident by setting {@code unassigned_at}
     * (only rows where it is still NULL). Returns the number of records closed (0 or 1 in normal
     * operation). Runs in the caller's transaction so it is atomic with the new record + audit.
     */
    int closeActive(UUID incidentId, Instant unassignedAt);

    /** Assignment history for an incident, oldest first (read-only). */
    List<IncidentAssignment> findByIncidentId(UUID incidentId);
}
