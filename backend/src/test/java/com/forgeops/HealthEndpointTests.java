package com.forgeops;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.testsupport.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the Actuator health endpoint responds and reports UP with the full context
 * (datasource + Flyway) started against a real PostgreSQL via Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class HealthEndpointTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
