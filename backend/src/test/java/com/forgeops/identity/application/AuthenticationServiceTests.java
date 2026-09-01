package com.forgeops.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationService}: resolving a validated token to an
 * authenticated principal, enforcing the database-authoritative account-status rule
 * (SECURITY_DESIGN.md §12), and taking roles from the token claim (not the persisted user).
 * Uses an in-memory repository fake — no database or crypto. Synthetic data.
 */
class AuthenticationServiceTests {

    private static final UUID ALICE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    private static final class InMemoryUsers implements UserRepository {
        final Map<UUID, User> byId = new HashMap<>();
        public User save(User u) { byId.put(u.id(), u); return u; }
        public Optional<User> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        public Optional<User> findByUsername(String u) { return Optional.empty(); }
        public boolean existsByUsername(String u) { return false; }
    }

    private final InMemoryUsers users = new InMemoryUsers();
    private final AuthenticationService service = new AuthenticationService(users);

    private User alice(AccountStatus status, Set<Role> roles) {
        return new User(ALICE_ID, "alice",
                PasswordHash.ofEncoded("$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$c29tZWhhc2h2YWx1ZQ"),
                status, roles, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void resolvesActiveUserToPrincipalWithTokenRoles() {
        // Persisted user has ADMIN; the token asserts ENGINEER. Per §12 the principal's
        // roles come from the TOKEN, not from the persisted user.
        users.save(alice(AccountStatus.ACTIVE, EnumSet.of(Role.ADMIN)));
        var token = new ValidatedAccessToken(ALICE_ID, Set.of(Role.ENGINEER), "jti-1");

        AuthenticatedUser principal = service.authenticate(token);

        assertThat(principal.userId()).isEqualTo(ALICE_ID);
        assertThat(principal.roles()).containsExactly(Role.ENGINEER);
    }

    @Test
    void rejectsUnknownSubject() {
        var token = new ValidatedAccessToken(ALICE_ID, Set.of(Role.ENGINEER), "jti-1");

        assertThatThrownBy(() -> service.authenticate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsDeactivatedUserEvenWithValidToken() {
        users.save(alice(AccountStatus.DEACTIVATED, EnumSet.of(Role.ENGINEER)));
        var token = new ValidatedAccessToken(ALICE_ID, Set.of(Role.ENGINEER), "jti-1");

        assertThatThrownBy(() -> service.authenticate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }
}
