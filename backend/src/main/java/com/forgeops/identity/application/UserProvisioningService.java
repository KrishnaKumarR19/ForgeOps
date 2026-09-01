package com.forgeops.identity.application;

import com.forgeops.common.id.IdGenerator;
import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.PasswordHasher;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application use case for provisioning a user (Phase 4.2 Slice 2, admin-created accounts
 * per SECURITY_DESIGN.md §3, ADR-0033).
 *
 * <p>This service does NOT accept a client-supplied identity: the user ID is always
 * server-generated via {@link IdGenerator} (UUID v7, ADR-0023). The password is hashed
 * with the Slice 1 {@link PasswordHasher} (Argon2id) and only the resulting
 * {@link PasswordHash} enters the {@link User}; plaintext never reaches persistence.
 *
 * <p>Provisioning is transactional so the user and its roles are persisted atomically
 * (the transaction boundary lives in the application layer, per ADR-0030 and
 * PERSISTENCE_MODEL.md §18). PostgreSQL's {@code uq_users_username} remains the
 * authoritative uniqueness boundary; a friendly pre-check is used, but the database is
 * the final arbiter.
 *
 * <p>Authorization (only an authenticated ADMIN may invoke this) is enforced in a later
 * security slice; this slice implements the capability, not the HTTP/authz surface.
 */
@Service
public class UserProvisioningService {

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public UserProvisioningService(UserRepository users,
                                   PasswordHasher passwordHasher,
                                   IdGenerator idGenerator,
                                   Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Provisions a new active user with the given username, password, and roles.
     *
     * @throws UsernameAlreadyExistsException if the username is already taken
     * @throws IllegalArgumentException       if username/password/roles are invalid
     */
    @Transactional
    public User provision(String username, CharSequence rawPassword, Set<Role> roles) {
        String normalizedUsername = requireUsername(username);
        Set<Role> requestedRoles = requireRoles(roles);
        if (rawPassword == null || rawPassword.length() == 0) {
            throw new IllegalArgumentException("Password must not be empty");
        }

        // Friendly pre-check; the DB unique constraint remains authoritative.
        if (users.existsByUsername(normalizedUsername)) {
            throw new UsernameAlreadyExistsException(normalizedUsername);
        }

        UUID id = idGenerator.newId();
        PasswordHash passwordHash = passwordHasher.hash(rawPassword);
        User user = new User(
                id,
                normalizedUsername,
                passwordHash,
                AccountStatus.ACTIVE,
                requestedRoles,
                clock.instant());

        return users.save(user);
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        return username;
    }

    private static Set<Role> requireRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        // Defensive copy; Role is an enum so invalid values cannot reach persistence.
        return EnumSet.copyOf(roles);
    }
}
