package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.IncidentAssignment;
import com.forgeops.incidents.domain.IncidentAssignmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter for the append-only assignment history (ADR-0030). Maps between the
 * framework-free {@link IncidentAssignment} and {@link IncidentAssignmentEntity}. Runs inside the
 * caller's transaction so the new record + close-prior + audit are atomic.
 */
@Repository
class JpaIncidentAssignmentRepository implements IncidentAssignmentRepository {

    private final SpringDataIncidentAssignmentJpaRepository jpa;

    JpaIncidentAssignmentRepository(SpringDataIncidentAssignmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public IncidentAssignment save(IncidentAssignment a) {
        jpa.save(new IncidentAssignmentEntity(a.id(), a.incidentId(), a.assigneeId(),
                a.assignedBy(), a.assignedAt(), a.unassignedAt(), a.team()));
        return a;
    }

    @Override
    public int closeActive(UUID incidentId, Instant unassignedAt) {
        return jpa.closeActive(incidentId, unassignedAt);
    }

    @Override
    public List<IncidentAssignment> findByIncidentId(UUID incidentId) {
        return jpa.findByIncidentIdOrderByAssignedAtAsc(incidentId).stream()
                .map(JpaIncidentAssignmentRepository::toDomain)
                .toList();
    }

    private static IncidentAssignment toDomain(IncidentAssignmentEntity e) {
        return new IncidentAssignment(e.getId(), e.getIncidentId(), e.getAssigneeId(),
                e.getAssignedBy(), e.getAssignedAt(), e.getUnassignedAt(), e.getTeam());
    }
}
