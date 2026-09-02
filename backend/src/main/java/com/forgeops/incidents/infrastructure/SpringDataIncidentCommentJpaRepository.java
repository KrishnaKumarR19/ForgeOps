package com.forgeops.incidents.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link IncidentCommentEntity}. Package-private
 * (ModuleBoundaryTests). Domain contract: {@link
 * com.forgeops.incidents.domain.IncidentCommentRepository}. Insert + read only (append-only).
 */
interface SpringDataIncidentCommentJpaRepository extends JpaRepository<IncidentCommentEntity, UUID> {

    List<IncidentCommentEntity> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);
}
