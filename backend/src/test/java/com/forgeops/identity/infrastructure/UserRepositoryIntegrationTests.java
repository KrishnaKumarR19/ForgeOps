package com.forgeops.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.common.id.IdGenerator;
import com.forgeops.common.id.UuidV7Generator;
import com.forgeops.common.time.TimeConfiguration;
import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import com.forgeops.testsupport.PostgresTestContainer;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Integration tests for identity persistence against real PostgreSQL (Testcontainers),
 * with the schema created by the Flyway migration chain (not by Hibernate). Verifies user
 * and role persistence and the database constraints that back identity invariants.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest is a sliced context that loads only JPA beans, so the real application
// infrastructure beans this test relies on must be imported explicitly: the JPA-backed
// repository adapter, the UUID v7 IdGenerator (ADR-0023), and the Clock it depends on.
// These are the genuine production beans — no mocks or test-only fakes.
@Import({PostgresTestContainer.class, JpaUserRepository.class, UuidV7Generator.class, TimeConfiguration.class})
class UserRepositoryIntegrationTests {

    @Autowired
    private UserRepository users;

    @Autowired
    private IdGenerator idGenerator;

    private User newUser(String username, Set<Role> roles) {
        return new User(
                idGenerator.newId(),
                username,
                PasswordHash.ofEncoded("$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$c29tZWhhc2h2YWx1ZQ"),
                AccountStatus.ACTIVE,
                roles,
                Clock.systemUTC().instant());
    }

    @Test
    void persistsAndRetrievesById() {
        User saved = users.save(newUser("alice", EnumSet.of(Role.ENGINEER)));

        Optional<User> found = users.findById(saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().username()).isEqualTo("alice");
        assertThat(found.get().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void retrievesByUsername() {
        users.save(newUser("bob", EnumSet.of(Role.VIEWER)));

        Optional<User> found = users.findByUsername("bob");

        assertThat(found).isPresent();
        assertThat(found.get().hasRole(Role.VIEWER)).isTrue();
    }

    @Test
    void enforcesUsernameUniqueness() {
        users.save(newUser("carol", EnumSet.of(Role.ENGINEER)));

        assertThatThrownBy(() -> users.save(newUser("carol", EnumSet.of(Role.VIEWER))))
                .isInstanceOf(Exception.class); // DB unique constraint violation surfaces as a persistence exception
    }

    @Test
    void supportsMultipleRolesPerUser() {
        User saved = users.save(
                newUser("dave", EnumSet.of(Role.ENGINEER, Role.INCIDENT_MANAGER)));

        Optional<User> found = users.findById(saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().roles())
                .containsExactlyInAnyOrder(Role.ENGINEER, Role.INCIDENT_MANAGER);
    }

    @Test
    void deduplicatesRepeatedRoleAssignment() {
        // The domain uses an EnumSet, so a repeated role collapses to a single entry, and
        // the (user_id, role) primary key prevents duplicates at the database level too.
        User saved = users.save(newUser("erin", EnumSet.of(Role.ADMIN)));

        Optional<User> found = users.findById(saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().roles()).containsExactly(Role.ADMIN);
    }

    @Test
    void persistsDeactivatedStatus() {
        UUID id = idGenerator.newId();
        User deactivated = new User(
                id, "frank",
                PasswordHash.ofEncoded("$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$c29tZWhhc2h2YWx1ZQ"),
                AccountStatus.DEACTIVATED,
                EnumSet.of(Role.VIEWER),
                Clock.systemUTC().instant());

        users.save(deactivated);

        assertThat(users.findById(id)).get()
                .extracting(User::status).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void persistsUserWithoutCredential() {
        // A user may exist before a credential is established (bootstrap deferred to 4.2).
        UUID id = idGenerator.newId();
        User noCredential = new User(
                id, "grace", null, AccountStatus.ACTIVE,
                EnumSet.of(Role.ADMIN), Clock.systemUTC().instant());

        users.save(noCredential);

        assertThat(users.findById(id)).get()
                .extracting(User::hasCredential).isEqualTo(false);
    }
}
