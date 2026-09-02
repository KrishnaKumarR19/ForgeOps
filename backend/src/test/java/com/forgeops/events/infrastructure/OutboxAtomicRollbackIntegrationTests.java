package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.testsupport.PostgresTestContainer;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Proves the event+outbox acceptance is a single atomic transaction (INV-OUTBOX-001,
 * INV-EVENT-006): if the outbox write fails after the event write, the whole transaction
 * rolls back and <strong>neither</strong> a durable event nor an outbox row remains.
 *
 * <p>A {@link TestConfiguration} overrides the outbox repository with a {@code @Primary} bean
 * that throws on {@code save} — a controlled test seam that forces the failure inside the
 * acceptance transaction. This is not a weakening of behavior; it is the only way to exercise
 * the rollback path deterministically against a real database.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import({PostgresTestContainer.class, OutboxAtomicRollbackIntegrationTests.FailingOutboxConfig.class})
class OutboxAtomicRollbackIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @TestConfiguration
    static class FailingOutboxConfig {
        @Bean
        @Primary
        OutboxMessageRepository failingOutboxMessageRepository() {
            return new OutboxMessageRepository() {
                @Override
                public OutboxMessage save(OutboxMessage message) {
                    throw new IllegalStateException("Simulated outbox persistence failure");
                }

                @Override
                public java.util.List<OutboxMessage> claimPending(int batchSize, java.time.Instant now) {
                    return java.util.List.of();
                }

                @Override
                public void markPublished(java.util.UUID id, java.time.Instant publishedAt) {
                }

                @Override
                public void recordFailure(java.util.UUID id, int attempts,
                                          java.time.Instant nextAttemptAt, String lastError) {
                }
            };
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        rest = new TestRestTemplate();
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
        rest.getRestTemplate().setUriTemplateHandler(
                new DefaultUriBuilderFactory("http://localhost:" + port));
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_messages CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    @Test
    void outboxWriteFailureRollsBackTheEventToo() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        String body = "{\"service\":\"checkout\",\"environment\":\"production\","
                + "\"event_type\":\"http_5xx\",\"occurred_at\":\"2026-02-01T00:00:00Z\","
                + "\"payload\":{\"a\":1}}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.add("Idempotency-Key", "key-1");

        ResponseEntity<String> response = rest.exchange("/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);

        // The request fails (server error) because the outbox write threw inside the tx...
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        // ...and crucially NEITHER row is durable: the event insert rolled back with the outbox.
        assertThat(count("operational_events")).isZero();
        assertThat(count("outbox_messages")).isZero();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                Map.class);
        return (String) response.getBody().get("access_token");
    }
}
