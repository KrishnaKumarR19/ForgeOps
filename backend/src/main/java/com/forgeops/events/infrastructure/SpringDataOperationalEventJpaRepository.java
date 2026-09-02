package com.forgeops.events.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link OperationalEventEntity}. Package-private: the events
 * module's persistence internals are not visible outside {@code events.infrastructure}
 * (ModuleBoundaryTests). The domain-facing contract is {@link
 * com.forgeops.events.domain.OperationalEventRepository}, implemented by {@link
 * JpaOperationalEventRepository}.
 */
interface SpringDataOperationalEventJpaRepository extends JpaRepository<OperationalEventEntity, UUID> {

    Optional<OperationalEventEntity> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);
}
