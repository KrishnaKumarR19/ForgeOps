package com.forgeops.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import com.forgeops.testsupport.PostgresTestContainer;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for user provisioning against real PostgreSQL (Testcontainers), with
 * the schema created by Flyway. Exercises the full application path (provisioning service →
 * Argon2id hasher → repository → PostgreSQL). Bootstrap is disabled here so these tests
 * control provisioning explicitly; bootstrap is covered separately.
 *
 * <p>{@code @SpringBootTest} does not roll back its writes, and the PostgreSQL container is
 * shared across integration-test classes, so each test cleans the identity tables first to
 * stay order-independent. Cleaning does not alter the schema or the production uniqueness
 * constraint — it only resets test data.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class UserProvisioningIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanIdentityTables() {
        // TRUNCATE ... CASCADE also clears user_roles via the FK; keeps tests independent
        // of execution order without weakening the schema or constraints.
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    @Test
    void provisionsAndRetrievesUser() {
        User created = provisioning.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER));

        Optional<User> found = users.findById(created.id());
        assertThat(found).isPresent();
        assertThat(found.get().username()).isEqualTo("alice");
        assertThat(found.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(found.get().hasRole(Role.ENGINEER)).isTrue();
        // Only the encoded Argon2id hash is stored — never the plaintext.
        assertThat(found.get().passwordHash().encodedValue()).startsWith("$argon2id$");
        assertThat(found.get().passwordHash().encodedValue()).doesNotContain(PASSWORD);
    }

    @Test
    void enforcesUsernameUniquenessAtProvisioning() {
        provisioning.provision("carol", PASSWORD, EnumSet.of(Role.ENGINEER));

        assertThatThrownBy(() -> provisioning.provision("carol", PASSWORD, EnumSet.of(Role.VIEWER)))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    void persistsMultipleRoles() {
        User created = provisioning.provision(
                "dave", PASSWORD, EnumSet.of(Role.ENGINEER, Role.INCIDENT_MANAGER));

        Optional<User> found = users.findById(created.id());
        assertThat(found).isPresent();
        assertThat(found.get().roles())
                .containsExactlyInAnyOrder(Role.ENGINEER, Role.INCIDENT_MANAGER);
    }
}
