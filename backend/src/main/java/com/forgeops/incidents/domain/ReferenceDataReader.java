package com.forgeops.incidents.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only resolver for the shared Service/Environment reference data, used by incidents to
 * resolve/validate the service and environment of a manually created incident and to render
 * their keys on reads (PERSISTENCE_MODEL.md §4 — Service/Environment are a controlled reference
 * set). The incidents module owns its own port (rather than depending on the events module) so
 * module boundaries stay clean (ADR-0030); the infrastructure adapter reads the shared
 * reference tables.
 *
 * <p>Framework-free (ADR-0030). An unknown key resolves to empty — the application maps that to
 * a {@code 422} unknown-reference response (API_CONTRACTS.md §18/§19).
 */
public interface ReferenceDataReader {

    /** Resolves a service key to its reference id, or empty if unknown. */
    Optional<UUID> findServiceIdByKey(String serviceKey);

    /** Resolves an environment key to its reference id, or empty if unknown. */
    Optional<UUID> findEnvironmentIdByKey(String environmentKey);

    /** Resolves a service id back to its key (for read representations). */
    Optional<String> findServiceKeyById(UUID serviceId);

    /** Resolves an environment id back to its key (for read representations). */
    Optional<String> findEnvironmentKeyById(UUID environmentId);
}
