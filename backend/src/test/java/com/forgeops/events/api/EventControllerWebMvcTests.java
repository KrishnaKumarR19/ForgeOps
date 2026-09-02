package com.forgeops.events.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.events.application.AcceptedEvent;
import com.forgeops.events.application.EventIngestionService;
import com.forgeops.events.application.IdempotencyConflictException;
import com.forgeops.events.application.IngestEventCommand;
import com.forgeops.events.application.UnknownReferenceException;
import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.identity.application.AccessTokenValidator;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.AuthenticationService;
import com.forgeops.identity.application.InvalidAccessTokenException;
import com.forgeops.identity.application.ValidatedAccessToken;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.infrastructure.security.JwtAuthenticationFilter;
import com.forgeops.identity.infrastructure.security.ProblemDetailAccessDeniedHandler;
import com.forgeops.identity.infrastructure.security.ProblemDetailAuthenticationEntryPoint;
import com.forgeops.identity.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorization + validation boundary tests for {@code POST /api/v1/events} driven through
 * the real security filter chain via MockMvc (no database). Proves: VIEWER is forbidden
 * (403), unauthenticated is rejected (401), an allowed role succeeds (202), validation
 * failures are 400, an idempotency conflict is 409, and the producer identity comes from the
 * principal — a client cannot override it via body/headers. Only application ports are mocked.
 */
@WebMvcTest(controllers = EventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class})
class EventControllerWebMvcTests {

    private static final UUID PRINCIPAL_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventIngestionService ingestionService;
    @MockBean
    private AccessTokenValidator accessTokenValidator;
    @MockBean
    private AuthenticationService authenticationService;

    private void principalWithRoles(String token, Set<Role> roles) {
        var validated = new ValidatedAccessToken(PRINCIPAL_ID, roles, "jti-1");
        when(accessTokenValidator.validate(eq(token))).thenReturn(validated);
        when(authenticationService.authenticate(validated))
                .thenReturn(new AuthenticatedUser(PRINCIPAL_ID, roles));
    }

    private OperationalEvent sampleEvent() {
        return OperationalEvent.accepted(
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                PRINCIPAL_ID, null, "key-1",
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
    void unauthenticatedIsRejected401() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void viewerIsForbidden403() throws Exception {
        principalWithRoles("viewer-token", Set.of(Role.VIEWER));

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer viewer-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void engineerCanSubmitAndReceives202() throws Exception {
        principalWithRoles("eng-token", Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any())).thenReturn(new AcceptedEvent(sampleEvent(), false));

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer eng-token")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("018f0000-0000-7000-8000-0000000000e1"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.event_type").value("http_5xx"))
                .andExpect(jsonPath("$.payload_hash").doesNotExist());
    }

    @Test
    void incidentManagerCanSubmit() throws Exception {
        principalWithRoles("im-token", Set.of(Role.INCIDENT_MANAGER));
        when(ingestionService.ingest(any())).thenReturn(new AcceptedEvent(sampleEvent(), false));

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer im-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isAccepted());
    }

    @Test
    void missingRequiredFieldIsValidation400() throws Exception {
        principalWithRoles("eng-token", Set.of(Role.ENGINEER));
        // Missing "service".
        String body = "{\"environment\":\"prod\",\"event_type\":\"http_5xx\","
                + "\"occurred_at\":\"2026-02-01T00:00:00Z\",\"payload\":{\"a\":1}}";

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer eng-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSeverityIsValidation400() throws Exception {
        principalWithRoles("eng-token", Set.of(Role.ENGINEER));
        String body = "{\"service\":\"checkout\",\"environment\":\"prod\",\"event_type\":\"http_5xx\","
                + "\"occurred_at\":\"2026-02-01T00:00:00Z\",\"severity\":\"NOT_A_SEVERITY\","
                + "\"payload\":{\"a\":1}}";

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer eng-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyConflictIs409() throws Exception {
        principalWithRoles("eng-token", Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any()))
                .thenThrow(new IdempotencyConflictException("conflict"));

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer eng-token")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownReferenceIs422() throws Exception {
        principalWithRoles("eng-token", Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any()))
                .thenThrow(new UnknownReferenceException("Unknown service: nope"));

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer eng-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void producerIdentityComesFromPrincipalNotFromRequest() throws Exception {
        principalWithRoles("eng-token", Set.of(Role.ENGINEER));
        when(ingestionService.ingest(any())).thenReturn(new AcceptedEvent(sampleEvent(), false));

        // Client tries to inject a different client_id / user_id / role via body and headers.
        UUID attacker = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
        String body = "{\"service\":\"checkout\",\"environment\":\"prod\",\"event_type\":\"http_5xx\","
                + "\"occurred_at\":\"2026-02-01T00:00:00Z\",\"payload\":{\"a\":1},"
                + "\"client_id\":\"" + attacker + "\",\"user_id\":\"" + attacker + "\",\"role\":\"ADMIN\"}";

        mockMvc.perform(post("/api/v1/events").header("Authorization", "Bearer eng-token")
                        .header("X-User-Id", attacker.toString())
                        .header("X-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        // The command handed to the service must carry the PRINCIPAL's id, never the attacker's.
        var captor = org.mockito.ArgumentCaptor.forClass(IngestEventCommand.class);
        org.mockito.Mockito.verify(ingestionService).ingest(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().clientId())
                .isEqualTo(PRINCIPAL_ID);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().clientId())
                .isNotEqualTo(attacker);
    }
}
