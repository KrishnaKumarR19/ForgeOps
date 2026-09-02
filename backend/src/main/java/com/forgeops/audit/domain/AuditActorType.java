package com.forgeops.audit.domain;

/**
 * Who performed an audited action (PERSISTENCE_MODEL.md §12, DOMAIN_MODEL.md §14). A
 * {@code USER} action is attributed to an authenticated principal; a {@code SYSTEM} action
 * (e.g. future event-driven detection) has no user actor. Phase 7 Slice 2 only writes
 * {@code USER} audit entries.
 *
 * <p>Framework-free (ADR-0030).
 */
public enum AuditActorType {
    USER,
    SYSTEM
}
