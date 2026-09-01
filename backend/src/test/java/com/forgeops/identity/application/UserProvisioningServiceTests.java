package com.forgeops.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.common.id.IdGenerator;
import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.PasswordHasher;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserProvisioningService}. Uses an in-memory {@link UserRepository}
 * fake and a lightweight {@link PasswordHasher} fake (both fakes of ports, not mocks of a
 * security component) so provisioning logic is exercised without a database. The real
 * Argon2id behavior is covered by Argon2PasswordHasherTests and the integration tests.
 * Synthetic passwords only.
 */
class UserProvisioningServiceTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    /**
     * Test hasher: encodes as a modular-crypt-style string so the produced value is a
     * valid PasswordHash, is not the plaintext, and can be "verified" deterministically.
     */
    private static final class TestPasswordHasher implements PasswordHasher {
        @Override
        public PasswordHash hash(CharSequence rawPassword) {
            return PasswordHash.ofEncoded("$test$" + Integer.toHexString(rawPassword.toString().hashCode()));
        }

        @Override
        public boolean verify(CharSequence rawPassword, PasswordHash passwordHash) {
            return passwordHash != null
                    && passwordHash.encodedValue().equals("$test$" + Integer.toHexString(rawPassword.toString().hashCode()));
        }
    }

    /** Minimal in-memory UserRepository for unit testing the use case. */
    private static final class InMemoryUserRepository implements UserRepository {
        private final Map<UUID, User> byId = new HashMap<>();
        private final Map<String, User> byUsername = new HashMap<>();

        @Override
        public User save(User user) {
            byId.put(user.id(), user);
            byUsername.put(user.username(), user);
            return user;
        }

        @Override
        public Optional<User> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return Optional.ofNullable(byUsername.get(username));
        }

        @Override
        public boolean existsByUsername(String username) {
            return byUsername.containsKey(username);
        }
    }

    private final InMemoryUserRepository repo = new InMemoryUserRepository();
    private final PasswordHasher hasher = new TestPasswordHasher();
    private final IdGenerator fixedId = () -> UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private final UserProvisioningService service =
            new UserProvisioningService(repo, hasher, fixedId, clock);

    @Test
    void hashesPasswordAndNeverStoresPlaintext() {
        User user = service.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER));

        assertThat(user.hasCredential()).isTrue();
        // Provisioning stored a hash (via the PasswordHasher), never the plaintext.
        assertThat(user.passwordHash().encodedValue()).doesNotContain(PASSWORD);
        // The stored user's string form never reveals the password.
        assertThat(user.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void assignsServerGeneratedId() {
        User user = service.provision("bob", PASSWORD, EnumSet.of(Role.VIEWER));

        assertThat(user.id()).isEqualTo(UUID.fromString("018f0000-0000-7000-8000-000000000001"));
    }

    @Test
    void persistsRequestedRoles() {
        User user = service.provision("dave", PASSWORD, EnumSet.of(Role.ENGINEER, Role.INCIDENT_MANAGER));

        assertThat(user.roles()).containsExactlyInAnyOrder(Role.ENGINEER, Role.INCIDENT_MANAGER);
    }

    @Test
    void newUserIsActive() {
        User user = service.provision("erin", PASSWORD, EnumSet.of(Role.ADMIN));

        assertThat(user.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void rejectsDuplicateUsername() {
        service.provision("carol", PASSWORD, EnumSet.of(Role.ENGINEER));

        assertThatThrownBy(() -> service.provision("carol", PASSWORD, EnumSet.of(Role.VIEWER)))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    void rejectsEmptyPasswordAndRoles() {
        assertThatThrownBy(() -> service.provision("x", "", EnumSet.of(Role.VIEWER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.provision("y", PASSWORD, EnumSet.noneOf(Role.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provisionedHashVerifiesAgainstOriginalPassword() {
        User user = service.provision("frank", PASSWORD, EnumSet.of(Role.ENGINEER));

        // The provisioned hash verifies against the original password via the hasher.
        assertThat(hasher.verify(PASSWORD, user.passwordHash())).isTrue();
    }
}
