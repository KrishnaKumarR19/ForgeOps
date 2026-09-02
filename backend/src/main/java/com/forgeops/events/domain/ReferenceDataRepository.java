package com.forgeops.events.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for resolving the events module's Service/Environment reference data
 * (DOMAIN_MODEL.md §1.1/§7 — owned by the events module; PERSISTENCE_MODEL.md §4). An event
 * references exactly one known service and one known environment; ingestion resolves the
 * submitted keys to their reference ids and rejects unknown values (API_CONTRACTS.md §6 →
 * {@code 422}). This slice provisions the controlled set via migration and offers no
 * management API — this port is read-only.
 */
public interface ReferenceDataRepository {

    /** Resolves a service key to its reference id, or empty if the service is unknown. */
    Optional<UUID> findServiceIdByKey(String serviceKey);

    /** Resolves an environment key to its reference id, or empty if the environment is unknown. */
    Optional<UUID> findEnvironmentIdByKey(String environmentKey);
}
