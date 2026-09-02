package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link IncidentRepository} port (ADR-0030). Maps
 * between the framework-free {@link Incident} aggregate and {@link IncidentEntity}, keeping JPA
 * out of the domain.
 */
@Repository
class JpaIncidentRepository implements IncidentRepository {

    private final SpringDataIncidentJpaRepository jpa;

    JpaIncidentRepository(SpringDataIncidentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Incident save(Incident incident) {
        jpa.saveAndFlush(toEntity(incident));
        return incident;
    }

    @Override
    public Optional<Incident> findById(UUID id) {
        return jpa.findById(id).map(JpaIncidentRepository::toDomain);
    }

    @Override
    public int updateWithVersionCheck(Incident next, long expectedVersion) {
        return jpa.updateWithVersionCheck(
                next.id(),
                expectedVersion,
                next.version(),
                next.state().name(),
                next.severity().name(),
                next.resolvedAt().orElse(null),
                next.closedAt().orElse(null));
    }

    private static IncidentEntity toEntity(Incident i) {
        return new IncidentEntity(
                i.id(),
                i.title().orElse(null),
                i.serviceId(),
                i.environmentId(),
                i.failureSignature().orElse(null),
                i.severity(),
                i.state(),
                i.currentAssigneeId().orElse(null),
                i.version(),
                i.createdAt(),
                i.resolvedAt().orElse(null),
                i.closedAt().orElse(null));
    }

    private static Incident toDomain(IncidentEntity e) {
        return new Incident(
                e.getId(),
                e.getTitle(),
                e.getServiceId(),
                e.getEnvironmentId(),
                e.getFailureSignature(),
                e.getSeverity(),
                e.getState(),
                e.getCurrentAssigneeId(),
                e.getVersion(),
                e.getCreatedAt(),
                e.getResolvedAt(),
                e.getClosedAt());
    }
}
