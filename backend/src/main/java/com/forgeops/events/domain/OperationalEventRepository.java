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

    /**
     * Atomically marks an event {@code PROCESSED}, but <em>only</em> if it is currently
     * {@code RECEIVED} — the idempotency primitive for the asynchronous consumer (FR-RL-3,
     * FR-RL-10, INV-MSG-003). This is a single conditional {@code UPDATE ... WHERE id = ? AND
     * status = 'RECEIVED'}, so a check-then-update race cannot cause a duplicate effect:
     * concurrent or duplicate deliveries either transition the row exactly once or observe it
     * already {@code PROCESSED}.
     *
     * <p>PostgreSQL is authoritative for the processed state (INV-EVENT-003); no in-memory or
     * broker-side deduplication is used. The caller must invoke this inside its transaction so
     * the mark is committed before the message is acknowledged (INV-MSG-004).
     *
     * @param id the event's server-generated resource id (from the message body {@code
     *           event_id})
     * @return the {@link ProcessingOutcome}: {@code MARKED} when this call transitioned the row
     *         {@code RECEIVED → PROCESSED}; {@code ALREADY_PROCESSED} when a row exists but was
     *         already {@code PROCESSED} (a duplicate delivery — no additional effect);
     *         {@code NOT_FOUND} when no such event exists
     */
    ProcessingOutcome markProcessed(UUID id);

    /**
     * Atomically associates the event with {@code incidentId} <em>and</em> marks it
     * {@code PROCESSED}, but <em>only</em> if it is currently {@code RECEIVED} (Phase 7 Slice 4).
     * A single conditional {@code UPDATE ... SET incident_id = ?, status = 'PROCESSED' WHERE id
     * = ? AND status = 'RECEIVED'} — so the association is set exactly once and duplicate/
     * concurrent deliveries cannot re-associate or re-process. Must run inside the detection
     * transaction so the association commits atomically with the incident create/correlate and
     * its audit (INV-INC-007, INV-EVENT-006).
     *
     * @param id         the event id
     * @param incidentId the incident to associate
     * @return {@link ProcessingOutcome#MARKED} if this call performed the association+transition;
     *         {@link ProcessingOutcome#ALREADY_PROCESSED} if the row was no longer RECEIVED;
     *         {@link ProcessingOutcome#NOT_FOUND} if no such event exists
     */
    ProcessingOutcome associateIncidentAndMarkProcessed(UUID id, UUID incidentId);
}
