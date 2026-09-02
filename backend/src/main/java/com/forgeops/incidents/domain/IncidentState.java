package com.forgeops.incidents.domain;

/**
 * Lifecycle state of an incident (DOMAIN_MODEL.md §10, ARCHITECTURE.md §9, PERSISTENCE_MODEL.md
 * §9). The set is fixed: {@code OPEN, ACKNOWLEDGED, INVESTIGATING, MITIGATED, RESOLVED, CLOSED}
 * — no additional states ({@code CANCELLED} is deliberately not adopted).
 *
 * <p>This slice (Phase 7 Slice 1) only establishes the persisted state value; the database
 * enforces that {@code state} is one of these values (a CHECK), while the <em>transition</em>
 * rules (which state may move to which) are an application-enforced invariant (INV-INC-002)
 * implemented in a later slice. A newly created incident starts in {@link #OPEN}.
 *
 * <p>Framework-free (ADR-0030): a plain domain enum with no JPA/Spring/HTTP types.
 */
public enum IncidentState {
    OPEN,
    ACKNOWLEDGED,
    INVESTIGATING,
    MITIGATED,
    RESOLVED,
    CLOSED
}
