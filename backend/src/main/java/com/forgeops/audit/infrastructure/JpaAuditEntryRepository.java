package com.forgeops.audit.infrastructure;

import com.forgeops.audit.domain.AuditEntry;
import com.forgeops.audit.domain.AuditEntryRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link AuditEntryRepository} port (ADR-0030). Maps
 * the framework-free {@link AuditEntry} to {@link AuditEntryEntity} and inserts it. Runs inside
 * the caller's transaction so the audit insert commits atomically with the change it records
 * (INV-INC-007, ADR-0018).
 */
@Repository
class JpaAuditEntryRepository implements AuditEntryRepository {

    private final SpringDataAuditEntryJpaRepository jpa;

    JpaAuditEntryRepository(SpringDataAuditEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuditEntry save(AuditEntry entry) {
        jpa.save(new AuditEntryEntity(
                entry.id(),
                entry.actorId(),
                entry.actorType(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.occurredAt(),
                entry.oldValue(),
                entry.newValue(),
                entry.correlationId()));
        return entry;
    }
}
