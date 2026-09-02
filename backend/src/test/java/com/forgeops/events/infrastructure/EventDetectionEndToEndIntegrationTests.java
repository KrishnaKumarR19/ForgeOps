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
 * Full Phase 5 → 6 → 7 Slice 4 path against real PostgreSQL and RabbitMQ (Testcontainers):
 * REST ingestion → operational_events + outbox (atomic) → outbox publisher → RabbitMQ → consumer
 * → detection/correlation → incident created (then correlated) → event.incident_id set →
 * status PROCESSED. Proves the slices compose: an accepted event is asynchronously turned into
 * an incident through the real broker, and a second matching event correlates to the same
 * incident. The consumer listener is enabled; the publisher is driven explicitly. DB isolated;
 * bootstrap admin disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "forgeops.security.bootstrap-admin.enabled=false",
                "forgeops.events.consumer.enabled=true"
        })
@Import({PostgresTestContainer.class, RabbitMqTestContainer.class})
class EventDetectionEndToEndIntegrationTests {

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
        jdbcTemplate.execute("TRUNCATE TABLE audit_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
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
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private String submitEvent(String token, String idempotencyKey) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.add("Idempotency-Key", idempotencyKey);
        String body = "{\"service\":\"checkout\",\"environment\":\"production\","
                + "\"event_type\":\"http_5xx\",\"severity\":\"MAJOR\","
                + "\"failure_signature\":\"upstream timeout\","
                + "\"occurred_at\":\"2026-03-20T12:00:00Z\",\"payload\":{\"a\":1}}";
        ResponseEntity<String> resp = rest.exchange("/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return objectMapper.readTree(resp.getBody()).get("id").asText();
    }

    private String eventStatus(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM operational_events WHERE id = ?::uuid", String.class, eventId);
    }

    private String eventIncidentId(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT incident_id::text FROM operational_events WHERE id = ?::uuid", String.class, eventId);
    }

    @Test
    void acceptedEventBecomesIncidentThroughTheRealBroker() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        String eventId = submitEvent(token, "e2e-detect-1");
        // Hand the committed outbox row to RabbitMQ (the poll timer is disabled in tests).
        assertThat(publishService.publishBatch()).isEqualTo(1);

        // The consumer detects/creates an incident and marks the event PROCESSED with incident_id.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(eventStatus(eventId)).isEqualTo("PROCESSED");
            assertThat(eventIncidentId(eventId)).isNotNull();
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incidents", Long.class)).isEqualTo(1L);
        // A detection audit was written by the SYSTEM actor.
        Long createdAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE action='INCIDENT_CREATED' AND actor_type='SYSTEM'",
                Long.class);
        assertThat(createdAudits).isEqualTo(1L);

        // A second matching event (same service/environment/signature, within the window)
        // correlates to the SAME incident rather than creating a new one.
        String incidentId = eventIncidentId(eventId);
        String eventId2 = submitEvent(token, "e2e-detect-2");
        assertThat(publishService.publishBatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(eventStatus(eventId2)).isEqualTo("PROCESSED");
            assertThat(eventIncidentId(eventId2)).isEqualTo(incidentId);
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incidents", Long.class)).isEqualTo(1L);
    }
}
