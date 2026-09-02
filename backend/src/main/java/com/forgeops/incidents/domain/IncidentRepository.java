package com.forgeops.incidents.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for persisting and looking up incidents (ADR-0030). PostgreSQL is the
 * authoritative store; the infrastructure adapter implements this port and the domain depends
 * only on this interface and its own types (framework-free — no JPA/Spring/SQL types).
 *
 * <p>Phase 7 Slice 1 (persistence foundation) needs only {@code save} and {@code findById};
 * lifecycle/command/query methods are added by later slices when the corresponding behavior is
 * built — not speculatively here.
 */
public interface IncidentRepository {

    /** Persists a newly created incident and returns it (used for manual creation). */
    Incident save(Incident incident);

    /** Finds an incident by its identity. */
    Optional<Incident> findById(UUID id);

    /**
     * Applies a lifecycle/severity mutation with an optimistic-lock guard (Phase 7 Slice 2,
     * INV-INC-005, ADR-0028). Atomically updates the incident's {@code state}, {@code severity},
     * lifecycle timestamps, and {@code version} <em>only if</em> the stored row still has
     * {@code expectedVersion} — a single conditional {@code UPDATE ... WHERE id = ? AND version
     * = ?}. This is the compare-and-set that prevents lost updates: a concurrent writer that
     * already advanced the version causes this call to affect zero rows.
     *
     * <p>{@code next} is the already-transitioned aggregate (its {@code version} is
     * {@code expectedVersion + 1}); the update writes {@code next}'s fields. Must run inside the
     * caller's transaction so the mutation and its audit entry commit atomically.
     *
     * @param next            the transitioned incident to persist (version already incremented)
     * @param expectedVersion the version the caller observed (from the client's If-Match ETag)
     * @return the number of rows updated: {@code 1} if applied, {@code 0} if the stored version
     *         no longer matches (stale) or the row is absent
     */
    int updateWithVersionCheck(Incident next, long expectedVersion);

    /**
     * Assignment-specific optimistic-lock compare-and-set (Phase 7 Slice 3, INV-INC-005): updates
     * the incident's {@code current_assignee_id} and {@code version} only if the stored row still
     * has {@code expectedVersion}. {@code next.currentAssigneeId()} is the new assignee (empty for
     * unassign). Returns 1 if applied, 0 if stale/absent. Must run inside the caller's transaction
     * (alongside the assignment-history insert and audit entry).
     */
    int updateAssigneeWithVersionCheck(Incident next, long expectedVersion);
}
