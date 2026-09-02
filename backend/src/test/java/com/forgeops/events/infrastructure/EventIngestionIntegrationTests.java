package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.testsupport.PostgresTestContainer;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Event ingestion integration tests over real HTTP against PostgreSQL (Testcontainers).
 * Exercises the full path: JWT auth → authorization → validation → idempotency → persistence.
 * Verifies durable persistence + retrieval via the DB, the {@code (client_id, idempotency_key)}
 * uniqueness constraint, replay vs conflict, producer-scoped idempotency, and the 401/403
 * security boundary.
 *
 * <p>Uses Apache HttpClient 5 (repeatable request bodies) so negative POSTs that return
 * 401/403 are received instead of triggering the JDK client's streaming auth-retry. The
 * shared non-rolled-back DB is truncated before each test; bootstrap admin is disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class EventIngestionIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        rest = new TestRestTemplate();
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
        rest.getRestTemplate().setUriTemplateHandler(
                new DefaultUriBuilderFactory("http://localhost:" + port));
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    // ----- helpers -------------------------------------------------------------

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            h.add("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    // Uses seeded reference data (services 'checkout', environments 'production') from V2__events.sql.
    private String body(String eventType, String payloadJson) {
        return "{\"service\":\"checkout\",\"environment\":\"production\",\"event_type\":\"" + eventType
                + "\",\"occurred_at\":\"2026-02-01T00:00:00Z\",\"payload\":" + payloadJson + "}";
    }

    private String bodyWith(String service, String environment) {
        return "{\"service\":\"" + service + "\",\"environment\":\"" + environment
                + "\",\"event_type\":\"http_5xx\",\"occurred_at\":\"2026-02-01T00:00:00Z\","
                + "\"payload\":{\"a\":1}}";
    }

    private ResponseEntity<String> submit(String token, String idempotencyKey, String body) {
        return rest.exchange("/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(body, headers(token, idempotencyKey)), String.class);
    }

    private long eventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operational_events", Long.class);
    }

    // ----- tests ---------------------------------------------------------------

    @Test
    void engineerSubmitsEventPersistedAndRetrievable() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        ResponseEntity<String> response = submit(token, "key-1", body("http_5xx", "{\"a\":1}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(json.get("event_type").asText()).isEqualTo("http_5xx");
        assertThat(json.has("payload_hash")).isFalse();

        String id = json.get("id").asText();
        // Response exposes the service/environment keys.
        assertThat(json.get("service").asText()).isEqualTo("checkout");
        assertThat(json.get("environment").asText()).isEqualTo("production");
        // Durably persisted with the authenticated principal as client_id and a real service FK.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT e.client_id, s.key AS service_key, e.status, e.payload_hash "
                        + "FROM operational_events e JOIN services s ON s.id = e.service_id "
                        + "WHERE e.id = ?::uuid", id);
        assertThat(row.get("service_key")).isEqualTo("checkout");
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat((String) row.get("payload_hash")).isNotBlank();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void sameKeySamePayloadReplaysSameEvent() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        ResponseEntity<String> first = submit(token, "key-1", body("http_5xx", "{\"a\":1,\"b\":2}"));
        // Same payload, different key ordering.
        ResponseEntity<String> second = submit(token, "key-1", body("http_5xx", "{\"b\":2,\"a\":1}"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).isEqualTo(first.getBody()); // same event representation
        assertThat(eventCount()).isEqualTo(1); // uniqueness held; no second event
    }

    @Test
    void sameKeyDifferentPayloadIsConflict409() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        submit(token, "key-1", body("http_5xx", "{\"a\":1}"));
        ResponseEntity<String> conflict = submit(token, "key-1", body("http_5xx", "{\"a\":999}"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(eventCount()).isEqualTo(1); // original unchanged
    }

    @Test
    void idempotencyKeyIsScopedPerClient() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        provisioning.provision("mgr", PASSWORD, EnumSet.of(Role.INCIDENT_MANAGER));
        String engToken = login("eng");
        String mgrToken = login("mgr");

        // Both clients use the SAME key value with DIFFERENT payloads — must not collide.
        ResponseEntity<String> a = submit(engToken, "shared", body("http_5xx", "{\"a\":1}"));
        ResponseEntity<String> b = submit(mgrToken, "shared", body("http_5xx", "{\"a\":2}"));

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(eventCount()).isEqualTo(2);
    }

    @Test
    void viewerIsForbidden403() {
        provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));
        String token = login("view");

        ResponseEntity<String> response = submit(token, "key-1", body("http_5xx", "{\"a\":1}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(eventCount()).isZero();
    }

    @Test
    void unauthenticatedIsRejected401() {
        ResponseEntity<String> response = submit(null, "key-1", body("http_5xx", "{\"a\":1}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(eventCount()).isZero();
    }

    @Test
    void unknownServiceIsRejected422() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        ResponseEntity<String> response = submit(token, "key-1", bodyWith("no-such-service", "production"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(eventCount()).isZero();
    }

    @Test
    void unknownEnvironmentIsRejected422() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        ResponseEntity<String> response = submit(token, "key-1", bodyWith("checkout", "no-such-env"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(eventCount()).isZero();
    }

    @Test
    void invalidRequestMissingFieldIsRejected400() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        String missingService = "{\"environment\":\"prod\",\"event_type\":\"http_5xx\","
                + "\"occurred_at\":\"2026-02-01T00:00:00Z\",\"payload\":{\"a\":1}}";

        ResponseEntity<String> response = submit(token, "key-1", missingService);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(eventCount()).isZero();
    }

    @Test
    void submissionWithoutIdempotencyKeyCreatesDistinctEvents() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        submit(token, null, body("http_5xx", "{\"a\":1}"));
        submit(token, null, body("http_5xx", "{\"a\":1}"));

        // No key => cannot be recognized as a retry => two distinct events (ADR-0025).
        assertThat(eventCount()).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateSubmissionsYieldExactlyOneEvent() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        String requestBody = body("http_5xx", "{\"a\":1}");

        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                latch.await();
                return submit(token, "race-key", requestBody).getStatusCode().value();
            }));
        }
        latch.countDown();
        for (var f : futures) {
            int code = f.get();
            // Each concurrent duplicate is either the accepted event or its replay — never 409
            // (same payload) and never a second event.
            assertThat(code).isEqualTo(HttpStatus.ACCEPTED.value());
        }
        pool.shutdown();

        assertThat(eventCount()).isEqualTo(1); // DB uniqueness guaranteed exactly one
    }
}
