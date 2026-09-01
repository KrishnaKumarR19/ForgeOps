package com.forgeops.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.identity.domain.Role;
import com.forgeops.testsupport.PostgresTestContainer;
import com.nimbusds.jwt.SignedJWT;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for login against real PostgreSQL (Testcontainers). Provisions a user
 * through the real provisioning path (Argon2id hash persisted), then logs in and inspects
 * the issued RS256 token. Uses the test-only JWT keys from {@code src/test/resources}.
 * Bootstrap is disabled so the tests control user creation.
 *
 * <p>The identity tables are cleaned before each test so the shared, non-rolled-back
 * {@code @SpringBootTest} database stays order-independent across integration classes.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class LoginIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private LoginService login;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanIdentityTables() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    @Test
    void persistedUserCanLogInAndTokenReflectsPersistedRoles() throws Exception {
        var created = provisioning.provision(
                "alice", PASSWORD, EnumSet.of(Role.ENGINEER, Role.INCIDENT_MANAGER));

        IssuedAccessToken token = login.login("alice", PASSWORD);

        SignedJWT jwt = SignedJWT.parse(token.token());
        var claims = jwt.getJWTClaimsSet();
        // sub is the persisted server-generated user id; roles come from the persisted model.
        assertThat(claims.getSubject()).isEqualTo(created.id().toString());
        assertThat(claims.getStringListClaim("roles"))
                .containsExactlyInAnyOrder("ENGINEER", "INCIDENT_MANAGER");
        assertThat(claims.getIssuer()).isEqualTo("forgeops-test");
    }

    @Test
    void wrongPasswordAgainstPersistedArgon2idHashIsRejected() {
        provisioning.provision("bob", PASSWORD, EnumSet.of(Role.VIEWER));

        assertThatThrownBy(() -> login.login("bob", "WrongPassword"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void unknownUserIsRejected() {
        assertThatThrownBy(() -> login.login("ghost", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
