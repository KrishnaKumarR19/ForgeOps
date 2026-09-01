package com.forgeops.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.PasswordHasher;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoginService}. Uses in-memory fakes of the ports (repository,
 * password hasher, token issuer) so the login logic — unknown user, disabled user, wrong
 * password, and success — is exercised without a database or real crypto. Synthetic data.
 */
class LoginServiceTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";
    private static final UUID ALICE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    /** Test PasswordHasher: encodes deterministically; verify() matches on the same input. */
    private static final class TestHasher implements PasswordHasher {
        @Override
        public PasswordHash hash(CharSequence raw) {
            return PasswordHash.ofEncoded("$test$" + raw);
        }

        @Override
        public boolean verify(CharSequence raw, PasswordHash hash) {
            return hash != null && hash.encodedValue().equals("$test$" + raw);
        }
    }

    private static final class InMemoryUsers implements UserRepository {
        final Map<String, User> byUsername = new HashMap<>();
        public User save(User u) { byUsername.put(u.username(), u); return u; }
        public Optional<User> findById(UUID id) { return Optional.empty(); }
        public Optional<User> findByUsername(String u) { return Optional.ofNullable(byUsername.get(u)); }
        public boolean existsByUsername(String u) { return byUsername.containsKey(u); }
    }

    private final InMemoryUsers users = new InMemoryUsers();
    private final PasswordHasher hasher = new TestHasher();
    private final AccessTokenIssuer issuer = u -> new IssuedAccessToken("token-for-" + u.id(), 900);
    private final LoginService login = new LoginService(users, hasher, issuer);

    private User activeAlice() {
        return new User(ALICE_ID, "alice", hasher.hash(PASSWORD),
                AccountStatus.ACTIVE, EnumSet.of(Role.ENGINEER), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void succeedsWithCorrectPassword() {
        users.save(activeAlice());

        IssuedAccessToken token = login.login("alice", PASSWORD);

        assertThat(token.token()).isEqualTo("token-for-" + ALICE_ID);
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsWrongPassword() {
        users.save(activeAlice());

        assertThatThrownBy(() -> login.login("alice", "WrongPassword"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsUnknownUser() {
        assertThatThrownBy(() -> login.login("nobody", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsDisabledUser() {
        users.save(new User(ALICE_ID, "alice", hasher.hash(PASSWORD),
                AccountStatus.DEACTIVATED, EnumSet.of(Role.ENGINEER),
                Instant.parse("2026-01-01T00:00:00Z")));

        assertThatThrownBy(() -> login.login("alice", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> login.login("", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> login.login("alice", ""))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
