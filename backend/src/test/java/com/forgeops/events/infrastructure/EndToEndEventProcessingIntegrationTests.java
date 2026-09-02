package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.events.application.OutboxPublishService;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.testsupport.PostgresTestContainer;
import com.forgeops.testsupport.RabbitMqTestContainer;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Full Phase 5 → Phase 6 path against real PostgreSQL and RabbitMQ (Testcontainers):
 * REST ingestion → {@code operational_events} + {@code outbox_messages} (atomic) → outbox
 * publisher → RabbitMQ → consumer → {@code status = PROCESSED}. Proves the slices compose:
 * an accepted event is asynchronously processed exactly once through the real broker.
 *
 * <p>The background publisher poll timer is disabled (as in all tests); the publisher is
 * driven explicitly via {@link OutboxPublishService#publishBatch()} to hand the committed
 * outbox row to RabbitMQ deterministically. The consumer listener is enabled and does the
 * rest. DB isolated via TRUNCATE; bootstrap admin disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "forgeops.security.bootstrap-admin.enabled=false",
                "forgeops.events.consumer.enabled=true"
        })
@Import({PostgresTestContainer.class, RabbitMqTestContainer.class})
class EndToEndEventProcessingIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private OutboxPublishService publishService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

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
        while (rabbitTemplate.receive("forgeops.events.processing", 200) != null) {
            // drain
        }
    }

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    @Test
    void acceptedEventIsAsynchronouslyProcessedThroughTheRealBroker() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.add("Idempotency-Key", "e2e-key-1");
        String body = "{\"service\":\"checkout\",\"environment\":\"production\","
                + "\"event_type\":\"http_5xx\",\"occurred_at\":\"2026-03-01T00:00:00Z\","
                + "\"payload\":{\"a\":1}}";

        ResponseEntity<String> response = rest.exchange("/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode json = objectMapper.readTree(response.getBody());
        String eventId = json.get("id").asText();
        assertThat(json.get("status").asText()).isEqualTo("RECEIVED");

        // Hand the committed outbox row to RabbitMQ (the poll timer is disabled in tests).
        int published = publishService.publishBatch();
        assertThat(published).isEqualTo(1);

        // The consumer processes it asynchronously: RECEIVED → PROCESSED.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM operational_events WHERE id = ?::uuid", String.class, eventId);
            assertThat(status).isEqualTo("PROCESSED");
        });

        // The outbox row was marked PUBLISHED by the publisher; the message was consumed/acked.
        String outboxStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_messages WHERE aggregate_id = ?::uuid",
                String.class, eventId);
        assertThat(outboxStatus).isEqualTo("PUBLISHED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive("forgeops.events.processing", 200)).isNull());
    }
}
