package com.forgeops.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers PostgreSQL configuration for integration and context tests.
 *
 * <p>Provides a real PostgreSQL instance so tests exercise actual database behavior
 * (constraints, migrations) rather than mocks. The {@code @ServiceConnection} bean wires
 * Spring Boot's datasource to the container automatically — no developer-local database and
 * no committed connection state. This is the only source of a database in tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}
