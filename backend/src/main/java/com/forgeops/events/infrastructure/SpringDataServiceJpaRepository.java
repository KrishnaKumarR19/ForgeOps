package com.forgeops.events.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link ServiceEntity} (package-private module internal). */
interface SpringDataServiceJpaRepository extends JpaRepository<ServiceEntity, UUID> {

    Optional<ServiceEntity> findByKey(String key);
}
