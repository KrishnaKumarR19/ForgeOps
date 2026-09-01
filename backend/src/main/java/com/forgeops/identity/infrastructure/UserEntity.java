package com.forgeops.identity.infrastructure;

import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA persistence entity for a user, kept separate from the {@link
 * com.forgeops.identity.domain.User} domain aggregate (ADR-0035) so Hibernate/JPA concerns
 * do not leak into the framework-free domain. Mapped to the {@code users} table and the
 * {@code user_roles} element collection created by the Flyway migration.
 *
 * <p>The schema is owned by the migration; {@code ddl-auto=validate} ensures this mapping
 * matches it. There is intentionally no plaintext password field — only {@code password_hash}.
 */
@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, updatable = false)
    private String username;

    /** Encoded password hash only (nullable until a credential is set in Phase 4.2). */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "user_roles",
            joinColumns = @JoinColumn(
                    name = "user_id",
                    foreignKey = @ForeignKey(name = "fk_user_roles_user")))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserEntity() {
        // Required by JPA.
    }

    UserEntity(UUID id, String username, String passwordHash, AccountStatus status,
               Set<Role> roles, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.status = status;
        this.roles = roles == null ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getUsername() {
        return username;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    AccountStatus getStatus() {
        return status;
    }

    Set<Role> getRoles() {
        return roles;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
