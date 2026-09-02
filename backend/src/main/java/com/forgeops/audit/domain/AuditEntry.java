package com.forgeops.audit.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An append-only audit record of a significant change (PERSISTENCE_MODEL.md §12, DOMAIN_MODEL.md
 * §14, INV-INC-003/007, ADR-0018). Captures who acted, what happened, which resource changed,
 * when, and the before/after values (as JSON text) plus a correlation id. Audit entries are
 * never updated or deleted (INV-INC-008); the store only inserts them.
 *
 * <p>{@code resourceId} is a <strong>polymorphic soft reference</strong> (PERSISTENCE_MODEL.md
 * §17): it may point at different resource kinds ({@code resourceType}), so it is not a single
 * foreign key — referential soundness is an application responsibility. {@code actorId} is
 * empty for {@code SYSTEM} actions.
 *
 * <p>Framework-free (ADR-0030): a plain domain value with no JPA/Spring/JSON-library types.
 * {@code oldValue}/{@code newValue} are opaque JSON text produced by the caller.
 *
 * @param id            audit entry id (UUID v7, ADR-0023)
 * @param actorId       the acting user, or empty for a SYSTEM action
 * @param actorType     USER or SYSTEM
 * @param action        stable action name (e.g. {@code INCIDENT_STATE_CHANGED})
 * @param resourceType  the changed resource kind (e.g. {@code INCIDENT})
 * @param resourceId    the changed resource's id (polymorphic soft reference)
 * @param occurredAt    when the change happened (server clock)
 * @param oldValue      previous state as JSON text, or empty where not meaningful
 * @param newValue      new state as JSON text, or empty where not meaningful
 * @param correlationId request correlation id, or empty
 */
public record AuditEntry(
        UUID id,
        UUID actorId,
        AuditActorType actorType,
        String action,
        String resourceType,
        UUID resourceId,
        Instant occurredAt,
        String oldValue,
        String newValue,
        String correlationId) {

    public AuditEntry {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (actorType == null) {
            throw new IllegalArgumentException("actorType is required");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType is required");
        }
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
    }

    /** Convenience: the actor id if a USER action, empty for SYSTEM. */
    public Optional<UUID> actor() {
        return Optional.ofNullable(actorId);
    }
}
