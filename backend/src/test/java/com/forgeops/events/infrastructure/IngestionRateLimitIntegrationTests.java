package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.testsupport.PostgresTestContainer;
import java.util.EnumSet;
import java.util.Map;
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
 * Integration proof that ingestion rate limiting (Phase 8 Slice 1, FR-RL-6) is enforced over
 * real HTTP against PostgreSQL (Testcontainers) and that a rate-limited request is rejected
 * <strong>before</strong> any business persistence — no {@code operational_events} row and no
 * {@code outbox_messages} row is created for a 429. A configured limit of 2/min is used so the
 * test does not send 60+ requests. Normal accepted requests retain their existing idempotency +
 * outbox behavior (rate limiting is protective, outside the business transaction).
 *
 * <p>Uses Apache HttpClient 5 (repeatable bodies) so the 429/401 responses are received rather
 * than triggering the JDK client's streaming auth-retry. DB truncated per test; bootstrap admin
 * disabled; consumer/publisher timers disabled so nothing races the assertions.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "forgeops.security.bootstrap-admin.enabled=false",
                "forgeops.events.consumer.enabled=false",
                "forgeops.outbox.publisher.enabled=false",
                "forgeops.rate-limit.ingestion.enabled=true",
                "forgeops.rate-limit.ingestion.limit=2",
                "forgeops.rate-limit.ingestion.window=PT1M"
        })
@Import(PostgresTestContainer.class)
class IngestionRateLimitIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.forgeops.events.application.RateLimitProperties rateLimitProperties;
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

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

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    /** Distinct payloads (no idempotency key) so each request is a genuinely new submission. */
    private ResponseEntity<String> submit(String token, int marker) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        String body = "{\"service\":\"checkout\",\"environment\":\"production\","
                + "\"event_type\":\"http_5xx\",\"occurred_at\":\"2026-02-01T00:00:00Z\","
                + "\"payload\":{\"n\":" + marker + "}}";
        return rest.exchange("/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private long eventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operational_events", Long.class);
    }

    private long outboxCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_messages", Long.class);
    }

    @Test
    void rateLimitedRequestIsRejectedBeforePersistence() {
        // Diagnostic context surfaced in any assertion failure (CI logs are not otherwise
        // accessible): the effective bound config and whether the rate-limit wiring is present.
        boolean webConfigPresent = !applicationContext
                .getBeansOfType(RateLimitWebConfig.class).isEmpty();
        boolean limiterPresent = !applicationContext
                .getBeansOfType(com.forgeops.events.application.RateLimiter.class).isEmpty();
        String diag = "effectiveLimit=" + rateLimitProperties.limit()
                + " enabled=" + rateLimitProperties.isEnabled()
                + " window=" + rateLimitProperties.window()
                + " rateLimitWebConfigBean=" + webConfigPresent
                + " rateLimiterBean=" + limiterPresent;

        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        // Two under-limit submissions succeed and persist (+ their outbox rows).
        assertThat(submit(token, 1).getStatusCode()).as(diag).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(submit(token, 2).getStatusCode()).as(diag).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(eventCount()).as(diag).isEqualTo(2);
        assertThat(outboxCount()).as(diag).isEqualTo(2);

        // The third exceeds the limit → 429 with Retry-After, and NOTHING is persisted for it.
        ResponseEntity<String> limited = submit(token, 3);
        assertThat(limited.getStatusCode())
                .as(diag + " thirdBody=" + limited.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();

        // No extra event or outbox row was created by the rejected request (limiter is outside
        // the business transaction — it runs before it).
        assertThat(eventCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void acceptedRequestsRetainIdempotencyAndOutboxBehavior() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        // Same idempotency key + same payload twice, within the limit → one event, one outbox,
        // second is a replay (202) — rate limiting does not disturb idempotency.
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.add("Idempotency-Key", "key-rl");
        String body = "{\"service\":\"checkout\",\"environment\":\"production\","
                + "\"event_type\":\"http_5xx\",\"occurred_at\":\"2026-02-01T00:00:00Z\","
                + "\"payload\":{\"a\":1}}";
        HttpEntity<String> req = new HttpEntity<>(body, h);

        ResponseEntity<String> first = rest.exchange("/api/v1/events", HttpMethod.POST, req, String.class);
        ResponseEntity<String> second = rest.exchange("/api/v1/events", HttpMethod.POST, req, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).isEqualTo(first.getBody()); // replay of the same event
        assertThat(eventCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }
}
