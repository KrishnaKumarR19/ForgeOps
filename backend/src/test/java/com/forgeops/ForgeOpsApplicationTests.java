package com.forgeops;

import com.forgeops.testsupport.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies that the Spring application context loads (now including the JPA datasource and
 * Flyway migrations against a real PostgreSQL via Testcontainers). If the context or
 * migration chain fails, the build fails. No business behavior is exercised.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class ForgeOpsApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: success is the context starting without error.
    }
}
