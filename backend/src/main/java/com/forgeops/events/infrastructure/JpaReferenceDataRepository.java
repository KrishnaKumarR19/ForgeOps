package com.forgeops.events.infrastructure;

import com.forgeops.events.domain.ReferenceDataRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link ReferenceDataRepository} port (ADR-0030).
 * Read-only key→id resolution for the events module's Service/Environment reference data.
 */
@Repository
class JpaReferenceDataRepository implements ReferenceDataRepository {

    private final SpringDataServiceJpaRepository services;
    private final SpringDataEnvironmentJpaRepository environments;

    JpaReferenceDataRepository(SpringDataServiceJpaRepository services,
                               SpringDataEnvironmentJpaRepository environments) {
        this.services = services;
        this.environments = environments;
    }

    @Override
    public Optional<UUID> findServiceIdByKey(String serviceKey) {
        return services.findByKey(serviceKey).map(ServiceEntity::getId);
    }

    @Override
    public Optional<UUID> findEnvironmentIdByKey(String environmentKey) {
        return environments.findByKey(environmentKey).map(EnvironmentEntity::getId);
    }
}
