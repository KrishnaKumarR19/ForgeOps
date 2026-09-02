package com.forgeops.audit.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AuditEntryEntity}. Package-private: audit persistence
 * internals are not visible outside {@code audit.infrastructure} (ModuleBoundaryTests). The
 * domain-facing contract is {@link com.forgeops.audit.domain.AuditEntryRepository}, implemented
 * by {@link JpaAuditEntryRepository}. Only insertion is used (append-only).
 */
interface SpringDataAuditEntryJpaRepository extends JpaRepository<AuditEntryEntity, UUID> {
}
