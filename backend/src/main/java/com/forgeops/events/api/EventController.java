package com.forgeops.events.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.common.correlation.CorrelationIdFilter;
import com.forgeops.events.application.AcceptedEvent;
import com.forgeops.events.application.EventIngestionService;
import com.forgeops.events.application.IdempotencyConflictException;
import com.forgeops.events.application.IngestEventCommand;
import com.forgeops.events.application.InvalidPayloadException;
import com.forgeops.events.application.UnknownReferenceException;
import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.identity.application.AuthenticatedUser;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event ingestion API (API_CONTRACTS.md §6). {@code POST /api/v1/events} accepts an
 * operational event from an authenticated ADMIN/ENGINEER/INCIDENT_MANAGER (VIEWER is denied
 * with {@code 403} by the security filter chain). The producer identity ({@code client_id})
 * is taken from the JWT principal, never from the request (SECURITY_DESIGN.md §9).
 *
 * <p>Success is {@code 202 Accepted} with the accepted-event representation — acceptance
 * arranges (future) asynchronous processing and does not assert that processing ran
 * (INV-EVENT-007). Validation failures map to {@code 400}, an idempotency-key/payload
 * conflict to {@code 409} (RFC 9457), both carrying the correlation id. This slice performs
 * no authorization beyond the URL rule and no incident/outbox/async work.
 */
@RestController
@RequestMapping("/api/v1/events")
class EventController {

    private final EventIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    EventController(EventIngestionService ingestionService, ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    ResponseEntity<AcceptedEventResponse> submit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitEventRequest request) {

        IngestEventCommand command = new IngestEventCommand(
                principal.userId(),                 // producer identity from the JWT principal
                emptyToNull(idempotencyKey),
                request.service(),
                request.environment(),
                request.eventType(),
                parseSeverity(request.severity()),
                request.producerEventId(),
                request.failureSignature(),
                request.occurredAt(),
                request.payload());

        AcceptedEvent accepted = ingestionService.ingest(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(accepted.event()));
    }

    private AcceptedEventResponse toResponse(OperationalEvent e) {
        return new AcceptedEventResponse(
                e.id().toString(),
                e.service(),
                e.environment(),
                e.eventType(),
                e.severity().map(Enum::name).orElse(null),
                e.occurredAt(),
                e.receivedAt(),
                e.status().name(),
                e.incidentId().map(java.util.UUID::toString).orElse(null),
                parsePayload(e.payload()));
    }

    private JsonNode parsePayload(String canonicalJson) {
        try {
            return objectMapper.readTree(canonicalJson);
        } catch (JsonProcessingException ex) {
            // The stored payload is always canonical JSON we produced, so this is unreachable
            // in practice; fail loudly rather than emit a malformed body.
            throw new IllegalStateException("Stored payload is not valid JSON", ex);
        }
    }

    private static EventSeverity parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        try {
            return EventSeverity.valueOf(severity);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown severity: " + severity);
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Same key reused with a different payload → 409 Conflict (RFC 9457). */
    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Idempotency conflict");
        problem.setDetail("The Idempotency-Key has already been used with a different payload.");
        attachCorrelationId(problem);
        return problem;
    }

    /** Unknown service/environment reference → 422 Unprocessable Content (RFC 9457). */
    @ExceptionHandler(UnknownReferenceException.class)
    ProblemDetail handleUnknownReference() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Unknown reference");
        problem.setDetail("The service or environment does not reference known reference data.");
        attachCorrelationId(problem);
        return problem;
    }

    /** Invalid payload or field value → 400 Bad Request (RFC 9457). */
    @ExceptionHandler({InvalidPayloadException.class, IllegalArgumentException.class})
    ProblemDetail handleInvalidRequest() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail("One or more fields are invalid.");
        attachCorrelationId(problem);
        return problem;
    }

    private static void attachCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
