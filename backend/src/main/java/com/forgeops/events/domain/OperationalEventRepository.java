package com.forgeops.events.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for persisting and looking up operational events (ADR-0030). PostgreSQL is the
 * authoritative store (INV-EVENT-003); the infrastructure adapter implements this port. The
 * domain depends only on this interface and its own types.
 */
public interface OperationalEventRepository {

    /**
     * Persists a newly accepted event, flushing so the authoritative uniqueness constraint on
     * {@code (client_id, idempotency_key)} is enforced by PostgreSQL at save time.
     *
     * @throws DuplicateIdempotencyKeyException if the {@code (clientId, idempotencyKey)} pair
     *                                          already exists (a concurrent duplicate lost the
     *                                          race); the caller resolves replay vs conflict
     */
    OperationalEvent save(OperationalEvent event);

    /** Finds an event by its server-generated resource id. */
    Optional<OperationalEvent> findById(UUID id);

    /**
     * Finds the event previously accepted for this authenticated client and idempotency key,
     * if any. Used to distinguish a first submission from a retry. Idempotency is scoped to
     * the client (ADR-0025), so callers must pass the authenticated principal's id.
     */
    Optional<OperationalEvent> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);
}
