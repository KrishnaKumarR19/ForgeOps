package com.forgeops.events.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.forgeops.events.application.AcceptedEvent;
import com.forgeops.events.application.EventIngestionService;
import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.infrastructure.RateLimitConfiguration;
import com.forgeops.identity.application.AccessTokenValidator;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.AuthenticationService;
import com.forgeops.identity.application.ValidatedAccessToken;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.infrastructure.security.JwtAuthenticationFilter;
import com.forgeops.identity.infrastructure.security.ProblemDetailAccessDeniedHandler;
import com.forgeops.identity.infrastructure.security.ProblemDetailAuthenticationEntryPoint;
import com.forgeops.identity.infrastructure.security.SecurityConfig;
import com.forgeops.common.time.TimeConfiguration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP behavior tests for ingestion rate limiting (Phase 8 Slice 1, FR-RL-6) through the real
 * security filter chain + the real in-process limiter and interceptor (no database). A small
 * configured limit (2 per minute) proves the 429 contract without sending 60+ requests.
 *
 * <p>Proves: under-limit succeeds (202); exceeding returns 429 as RFC 9457 {@code
 * application/problem+json} with a {@code Retry-After} header and correlation id; unauthenticated
 * still returns 401 (limiter never runs before auth); the key is the authenticated principal, so
 * distinct principals have independent allowances and a client cannot alter the key via
 * headers/body; and a non-rate-limited endpoint (GET) is unaffected.
 */
@WebMvcTest(controllers = EventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class,
        TimeConfiguration.class, RateLimitConfiguration.class})
@TestPropertySource(properties = {
        "forgeops.rate-limit.ingestion.enabled=true",
        "forgeops.rate-limit.ingestion.limit=2",
        "forgeops.rate-limit.ingestion.window=PT1M"
})
class IngestionRateLimitWebMvcTests {

    // Each test uses fresh principal ids so the singleton limiter's per-principal buckets are not
    // shared across test methods (the limiter bean is reused within the @WebMvcTest context).
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EventIngestionService ingestionService;
    @MockBean
    private AccessTokenValidator accessTokenValidator;
    @MockBean
    private AuthenticationService authenticationService;

    private void principal(String token, UUID id, Set<Role> roles) {
        var validated = new ValidatedAccessToken(id, roles, "jti-" + token);
        when(accessTokenValidator.validate(eq(token))).thenReturn(validated);
        when(authenticationService.authenticate(validated))
                .thenReturn(new AuthenticatedUser(id, roles));
    }

    private OperationalEvent sampleEvent() {
        return OperationalEvent.accepted(
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                alice, null, null,
                UUID.fromString("018f1000-0000-7000-8000-000000000001"), "checkout",
                UUID.fromString("018f1001-0000-7000-8000-000000000001"), "prod", "http_5xx",
                EventSeverity.MAJOR, null, Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:01Z"), "{\"a\":1}", "hash");
    }

    private String validBody() {
        return "{\"service\":\"checkout\",\"environment\":\"prod\",\"event_type\":\"http_5xx\","
                + "\"occurred_at\":\"2026-02-01T00:00:00Z\",\"payload\":{\"a\":1}}";
    }

    @Test
    void underLimitSucceedsThenExceedingReturns429ProblemJsonWithRetryAfter() throws Exception {
        principal("alice-token", alice, Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any())).thenReturn(new AcceptedEvent(sampleEvent(), false));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer alice-token")
                            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer alice-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void unauthenticatedStillReturns401AndLimiterNeverRuns() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void distinctPrincipalsHaveIndependentAllowances() throws Exception {
        principal("alice-token", alice, Set.of(Role.ENGINEER));
        principal("bob-token", bob, Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any())).thenReturn(new AcceptedEvent(sampleEvent(), false));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer alice-token")
                            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer alice-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer bob-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isAccepted());
    }

    @Test
    void clientSuppliedIdentityCannotAlterTheRateLimitKey() throws Exception {
        principal("alice-token", alice, Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any())).thenReturn(new AcceptedEvent(sampleEvent(), false));

        UUID attacker = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer alice-token")
                            .header("X-User-Id", attacker.toString())
                            .header("X-Client-Id", attacker.toString())
                            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer alice-token")
                        .header("X-User-Id", attacker.toString())
                        .header("X-Client-Id", attacker.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void nonRateLimitedEndpointIsUnaffected() throws Exception {
        principal("alice-token", alice, Set.of(Role.VIEWER));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/events/whatever")
                            .header("Authorization", "Bearer alice-token"))
                    .andExpect(result -> {
                        int s = result.getResponse().getStatus();
                        if (s == 429) {
                            throw new AssertionError("GET must not be rate limited");
                        }
                    });
        }
    }
}
