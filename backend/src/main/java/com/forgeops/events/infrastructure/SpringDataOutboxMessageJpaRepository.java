package com.forgeops.events.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link OutboxMessageEntity}. Package-private: the events
 * module's persistence internals are not visible outside {@code events.infrastructure}
 * (ModuleBoundaryTests). The domain-facing contract is {@link
 * com.forgeops.events.domain.OutboxMessageRepository}.
 */
interface SpringDataOutboxMessageJpaRepository extends JpaRepository<OutboxMessageEntity, UUID> {
}
