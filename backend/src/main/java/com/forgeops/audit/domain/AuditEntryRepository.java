package com.forgeops.audit.domain;

/**
 * Domain port for the append-only audit store (ADR-0030, INV-INC-008). PostgreSQL is
 * authoritative. Only insertion is exposed — audit entries are never updated or deleted
 * from the domain path (PERSISTENCE_MODEL.md §19). The infrastructure adapter implements this
 * port; the domain/application layers depend only on this framework-free interface.
 *
 * <p>The call is expected to run inside the caller's transaction so the audit insert commits
 * atomically with the change it describes (INV-INC-007, ADR-0018).
 */
public interface AuditEntryRepository {

    /** Appends a new audit entry. */
    AuditEntry save(AuditEntry entry);
}
