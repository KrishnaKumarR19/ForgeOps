package com.forgeops.incidents.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link IncidentEntity}. Package-private: the incidents
 * module's persistence internals are not visible outside {@code incidents.infrastructure}
 * (ModuleBoundaryTests). The domain-facing contract is {@link
 * com.forgeops.incidents.domain.IncidentRepository}, implemented by {@link
 * JpaIncidentRepository}.
 */
interface SpringDataIncidentJpaRepository extends JpaRepository<IncidentEntity, UUID> {

    /**
     * Optimistic-lock compare-and-set (Phase 7 Slice 2, INV-INC-005, ADR-0028): applies the
     * mutation only if the stored {@code version} still equals {@code expectedVersion}. Native
     * enum values are written as text (matching the {@code EnumType.STRING} mapping and the
     * {@code ck_incidents_*} CHECKs). Returns the number of rows updated (1 = applied,
     * 0 = stale/absent). Must run inside a transaction.
     */
    @Modifying
    @Query(value = """
            UPDATE incidents
            SET state = :state,
                severity = :severity,
                resolved_at = :resolvedAt,
                closed_at = :closedAt,
                version = :newVersion
            WHERE id = :id AND version = :expectedVersion
            """, nativeQuery = true)
    int updateWithVersionCheck(@Param("id") UUID id,
                               @Param("expectedVersion") long expectedVersion,
                               @Param("newVersion") long newVersion,
                               @Param("state") String state,
                               @Param("severity") String severity,
                               @Param("resolvedAt") Instant resolvedAt,
                               @Param("closedAt") Instant closedAt);
}
