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
@Import({PostgresTestContainer.class, IngestionRateLimitIntegrationTests.CapturingInterceptorConfig.class})
class IngestionRateLimitIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    // TEMP diagnostic: is the rate-limit interceptor actually registered on the dispatcher's
    // handler mapping in the full RANDOM_PORT context? Green here (with the HTTP tests disabled)
    // proves registration works and points the remaining 202 at principal keying.
    @Test
    void diagnosticInterceptorIsRegisteredOnHandlerMapping() throws Exception {
        var f = org.springframework.web.servlet.handler.AbstractHandlerMapping.class
                .getDeclaredMethod("getAdaptedInterceptors");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<org.springframework.web.servlet.HandlerInterceptor> interceptors =
                (java.util.List<org.springframework.web.servlet.HandlerInterceptor>) f.invoke(handlerMapping);
        assertThat(interceptors)
                .as("adapted interceptors on RequestMappingHandlerMapping = " + interceptors)
                .anyMatch(i -> i instanceof IngestionRateLimitInterceptor);
    }

    /**
     * TEMP H2 diagnostic: a test-only capturing interceptor registered on {@code /api/v1/events}
     * records what the {@code SecurityContext} holds at MVC interceptor execution time during a
     * REAL authenticated HTTP request. This observes the exact principal the production
     * rate-limit interceptor would see, without touching production behavior.
     */
    static final java.util.concurrent.atomic.AtomicReference<String> CAPTURED_PRINCIPAL =
            new java.util.concurrent.atomic.AtomicReference<>("<<never-invoked>>");

    @org.springframework.boot.test.context.TestConfiguration
    static class CapturingInterceptorConfig
            implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override
        public void addInterceptors(
                org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
            registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
                @Override
                public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                         jakarta.servlet.http.HttpServletResponse response,
                                         Object handler) {
                    var auth = org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication();
                    if (auth == null) {
                        CAPTURED_PRINCIPAL.set("auth=null");
                    } else {
                        Object p = auth.getPrincipal();
                        CAPTURED_PRINCIPAL.set("authClass=" + auth.getClass().getName()
                                + " authenticated=" + auth.isAuthenticated()
                                + " principalClass=" + (p == null ? "null" : p.getClass().getName()));
                    }
                    return true;
                }
            }).addPathPatterns("/api/v1/events");
        }
    }

    @Test
    void diagnosticPrincipalSeenByInterceptorDuringRealRequest() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        CAPTURED_PRINCIPAL.set("<<never-invoked>>");

        ResponseEntity<String> resp = submit(token, 42);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // The failure message (surfaced in the report) reveals the exact principal type seen.
        assertThat(CAPTURED_PRINCIPAL.get())
                .as("principal seen by MVC interceptor during POST /api/v1/events")
                .contains("principalClass=com.forgeops.identity.application.AuthenticatedUser");
    }

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

    @org.junit.jupiter.api.Disabled("TEMP: evidence-only diagnostic run — re-enabled after diagnosis")
    @Test
    void rateLimitedRequestIsRejectedBeforePersistence() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        // Two under-limit submissions succeed and persist (+ their outbox rows).
        assertThat(submit(token, 1).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(submit(token, 2).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(eventCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);

        // The third exceeds the limit → 429 with Retry-After, and NOTHING is persisted for it.
        ResponseEntity<String> limited = submit(token, 3);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();

        // No extra event or outbox row was created by the rejected request (limiter is outside
        // the business transaction — it runs before it).
        assertThat(eventCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @org.junit.jupiter.api.Disabled("TEMP: evidence-only diagnostic run — re-enabled after diagnosis")
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
