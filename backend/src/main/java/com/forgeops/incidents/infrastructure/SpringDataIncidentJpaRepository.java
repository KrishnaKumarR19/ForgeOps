package com.forgeops.incidents.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link IncidentEntity}. Package-private: the incidents
 * module's persistence internals are not visible outside {@code incidents.infrastructure}
 * (ModuleBoundaryTests). The domain-facing contract is {@link
 * com.forgeops.incidents.domain.IncidentRepository}, implemented by {@link
 * JpaIncidentRepository}.
 */
interface SpringDataIncidentJpaRepository extends JpaRepository<IncidentEntity, UUID> {
}
