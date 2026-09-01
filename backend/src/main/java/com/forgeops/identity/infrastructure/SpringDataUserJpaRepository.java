package com.forgeops.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link UserEntity}. Internal to the identity
 * infrastructure layer; the rest of the application depends on the domain
 * {@link com.forgeops.identity.domain.UserRepository} port, not on this interface.
 */
interface SpringDataUserJpaRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);
}
