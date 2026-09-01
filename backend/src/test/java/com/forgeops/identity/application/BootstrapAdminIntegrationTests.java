package com.forgeops.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.domain.UserRepository;
import com.forgeops.testsupport.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration tests for the bootstrap administrator against real PostgreSQL
 * (Testcontainers). The bootstrap credentials here are synthetic test values supplied via
 * test properties — not real credentials, and not committed anywhere sensitive.
 *
 * <p>The application's {@code BootstrapAdminInitializer} runs once on startup; these tests
 * then re-invoke it to prove idempotency (no duplicate user/roles, no password change).
 */
@SpringBootTest(properties = {
        "forgeops.security.bootstrap-admin.enabled=true",
        "forgeops.security.bootstrap-admin.username=bootstrap-admin",
        "forgeops.security.bootstrap-admin.password=SyntheticBootstrapPassword123"
})
@Import(PostgresTestContainer.class)
class BootstrapAdminIntegrationTests {

    private static final String BOOTSTRAP_USERNAME = "bootstrap-admin";

    @Autowired
    private UserRepository users;

    @Autowired
    private BootstrapAdminInitializer initializer;

    @Test
    void bootstrapCreatesExactlyOneAdminAndIsIdempotent() {
        // Startup already ran the initializer once; the admin should exist as an ADMIN.
        assertThat(users.findByUsername(BOOTSTRAP_USERNAME)).isPresent();

        User first = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();
        String hashAfterFirstRun = first.passwordHash().encodedValue();
        assertThat(first.hasRole(Role.ADMIN)).isTrue();

        // Re-run the initializer twice: must be a no-op (idempotent).
        initializer.run(noArgs());
        initializer.run(noArgs());

        User afterReruns = users.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();
        // Same identity (no duplicate/replacement) and unchanged password hash.
        assertThat(afterReruns.id()).isEqualTo(first.id());
        assertThat(afterReruns.passwordHash().encodedValue()).isEqualTo(hashAfterFirstRun);
        assertThat(afterReruns.roles()).containsExactly(Role.ADMIN);
    }

    private static ApplicationArguments noArgs() {
        return new org.springframework.boot.DefaultApplicationArguments();
    }
}
