package com.forgeops.identity.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * User domain aggregate (framework-independent, per ADR-0030). Represents only the identity
 * state required now: identity, login username, optional password hash, account status,
 * roles, and a creation timestamp.
 *
 * <p>No speculative profile fields (email-verification, phone, avatar, preferences, etc.).
 * The password is represented only as a {@link PasswordHash} value object — there is no
 * plaintext field (SECURITY_DESIGN.md §4). The hash is optional so a user can exist before
 * its credential is established (bootstrap/credential creation is a later slice).
 */
public final class User {

    private final UUID id;
    private final String username;
    private PasswordHash passwordHash; // optional; null until a credential is set (Phase 4.2)
    private AccountStatus status;
    private final Set<Role> roles;
    private final Instant createdAt;

    public User(UUID id,
                String username,
                PasswordHash passwordHash,
                AccountStatus status,
                Set<Role> roles,
                Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = requireUsername(username);
        this.passwordHash = passwordHash; // may be null
        this.status = Objects.requireNonNull(status, "status");
        this.roles = roles == null || roles.isEmpty()
                ? EnumSet.noneOf(Role.class)
                : EnumSet.copyOf(roles);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        return username;
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    /** The password hash, if a credential has been established. Never plaintext. */
    public PasswordHash passwordHash() {
        return passwordHash;
    }

    public boolean hasCredential() {
        return passwordHash != null;
    }

    public AccountStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /** Unmodifiable view of the user's roles. */
    public Set<Role> roles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /** Excludes the password hash and is safe to log. */
    @Override
    public String toString() {
        return "User[id=" + id + ", username=" + username + ", status=" + status
                + ", roles=" + roles + "]";
    }
}
