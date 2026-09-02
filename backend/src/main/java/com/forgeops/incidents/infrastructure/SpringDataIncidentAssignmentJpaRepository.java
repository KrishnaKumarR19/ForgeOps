package com.forgeops.incidents.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link IncidentAssignmentEntity}. Package-private
 * (ModuleBoundaryTests). Domain contract: {@link
 * com.forgeops.incidents.domain.IncidentAssignmentRepository}.
 */
interface SpringDataIncidentAssignmentJpaRepository extends JpaRepository<IncidentAssignmentEntity, UUID> {

    List<IncidentAssignmentEntity> findByIncidentIdOrderByAssignedAtAsc(UUID incidentId);

    /**
     * Closes the currently-active assignment(s) for an incident (sets {@code unassigned_at} where
     * still NULL). Insert-only elsewhere; this is the single permitted mutation on history, used
     * transactionally during reassignment/unassign. Returns rows affected.
     */
    @Modifying
    @Query(value = """
            UPDATE incident_assignments
            SET unassigned_at = :unassignedAt
            WHERE incident_id = :incidentId AND unassigned_at IS NULL
            """, nativeQuery = true)
    int closeActive(@Param("incidentId") UUID incidentId, @Param("unassignedAt") Instant unassignedAt);
}
