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

    /** Persists a new or rehydrated incident and returns it. */
    Incident save(Incident incident);

    /** Finds an incident by its identity. */
    Optional<Incident> findById(UUID id);
}
