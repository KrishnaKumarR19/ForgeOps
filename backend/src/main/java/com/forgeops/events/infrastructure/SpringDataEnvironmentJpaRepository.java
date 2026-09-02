package com.forgeops.events.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link EnvironmentEntity} (package-private module internal). */
interface SpringDataEnvironmentJpaRepository extends JpaRepository<EnvironmentEntity, UUID> {

    Optional<EnvironmentEntity> findByKey(String key);
}
