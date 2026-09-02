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

    /**
     * Assignment compare-and-set (Phase 7 Slice 3, INV-INC-005): sets {@code current_assignee_id}
     * (may be NULL for unassign) and {@code version} only if the stored {@code version} matches
     * {@code expectedVersion}. Returns rows updated (1 = applied, 0 = stale/absent).
     */
    @Modifying
    @Query(value = """
            UPDATE incidents
            SET current_assignee_id = :assigneeId,
                version = :newVersion
            WHERE id = :id AND version = :expectedVersion
            """, nativeQuery = true)
    int updateAssigneeWithVersionCheck(@Param("id") UUID id,
                                       @Param("expectedVersion") long expectedVersion,
                                       @Param("newVersion") long newVersion,
                                       @Param("assigneeId") UUID assigneeId);

    /**
     * Active-incident correlation match (Phase 7 Slice 4). Same service/environment/signature, an
     * ACTIVE state, and the sliding window expressed as bounds on {@code created_at}:
     * {@code windowStart <= created_at <= receivedAt} (equivalent to
     * {@code created_at <= receivedAt <= created_at + window}, with no future-created incidents).
     * Newest wins. {@code LIMIT 1}.
     */
    @Query(value = """
            SELECT * FROM incidents
            WHERE service_id = :serviceId
              AND environment_id = :environmentId
              AND failure_signature = :signature
              AND state IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATED')
              AND created_at <= :receivedAt
              AND created_at >= :windowStart
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """, nativeQuery = true)
    IncidentEntity findActiveMatch(@Param("serviceId") UUID serviceId,
                                   @Param("environmentId") UUID environmentId,
                                   @Param("signature") String signature,
                                   @Param("receivedAt") Instant receivedAt,
                                   @Param("windowStart") Instant windowStart);
}
