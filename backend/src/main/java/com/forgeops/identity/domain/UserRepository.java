package com.forgeops.identity.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for {@link User} persistence, owned by the identity domain (ADR-0030).
 *
 * <p>The domain defines this interface in terms of domain types only; the JPA-backed
 * adapter lives in {@code identity.infrastructure}. No authentication logic lives here —
 * this is persistence access only for the Phase 4.1 slice.
 */
public interface UserRepository {

    /** Persists a new user. Uniqueness of the username is enforced by the database. */
    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
