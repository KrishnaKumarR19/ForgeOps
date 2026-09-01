package com.forgeops.identity.infrastructure;

import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link UserRepository} port (ADR-0030). Maps
 * between the framework-free {@link User} aggregate and {@link UserEntity}, keeping JPA out
 * of the domain. Transaction boundaries are owned by the application layer (later slices),
 * not invented here; simple single-repository operations rely on the default per-operation
 * transaction of Spring Data.
 */
@Repository
class JpaUserRepository implements UserRepository {

    private final SpringDataUserJpaRepository jpa;

    JpaUserRepository(SpringDataUserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public User save(User user) {
        // saveAndFlush (not save) so the INSERT reaches PostgreSQL as part of this call.
        // Plain save() only stages the entity in the persistence context and defers the
        // flush, which would hide authoritative DB constraint violations (e.g. the unique
        // username constraint uq_users_username) from the caller until an unpredictable
        // later flush. Flushing here surfaces such violations at the persistence boundary
        // at save time, keeping uniqueness enforcement in PostgreSQL rather than in Java.
        UserEntity saved = jpa.saveAndFlush(toEntity(user));
        return toDomain(saved);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id).map(JpaUserRepository::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpa.findByUsername(username).map(JpaUserRepository::toDomain);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpa.existsByUsername(username);
    }

    private static UserEntity toEntity(User user) {
        String encodedHash = user.hasCredential() ? user.passwordHash().encodedValue() : null;
        return new UserEntity(
                user.id(),
                user.username(),
                encodedHash,
                user.status(),
                user.roles(),
                user.createdAt());
    }

    private static User toDomain(UserEntity entity) {
        PasswordHash hash = entity.getPasswordHash() == null
                ? null
                : PasswordHash.ofEncoded(entity.getPasswordHash());
        return new User(
                entity.getId(),
                entity.getUsername(),
                hash,
                entity.getStatus(),
                entity.getRoles(),
                entity.getCreatedAt());
    }
}
