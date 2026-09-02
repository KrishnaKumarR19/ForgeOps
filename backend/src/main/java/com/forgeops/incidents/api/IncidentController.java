package com.forgeops.incidents.api;

import com.forgeops.common.correlation.CorrelationIdFilter;
import com.forgeops.incidents.application.CreateIncidentCommand;
import com.forgeops.incidents.application.ForbiddenAssignmentException;
import com.forgeops.incidents.application.IncidentNotFoundException;
import com.forgeops.incidents.application.IncidentService;
import com.forgeops.incidents.application.StaleIncidentVersionException;
import com.forgeops.incidents.application.UnknownReferenceException;
import com.forgeops.incidents.domain.CommentCategory;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentComment;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.IllegalIncidentTransitionException;
import com.forgeops.incidents.domain.ReferenceDataReader;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.domain.Role;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident management API (API_CONTRACTS.md §9/§10/§11/§26, Phase 7 Slice 2). Manual creation,
 * a single-incident read, and the explicit lifecycle command endpoints
 * (acknowledge/investigate/mitigate/resolve/close/severity — ADR-0027; no generic PATCH).
 *
 * <p>Optimistic concurrency (ADR-0028): {@code GET} returns a strong {@code ETag} of the
 * incident version; every mutation requires {@code If-Match} — missing → {@code 428}, stale →
 * {@code 412}. An invalid lifecycle transition → {@code 409}. RBAC is enforced by the security
 * filter chain (URL rules); the actor is the JWT principal, never the request (INV-SEC-005).
 *
 * <p>The controller is thin: it validates the HTTP shape, parses {@code If-Match}, obtains the
 * principal, calls {@link IncidentService}, and maps results/exceptions to RFC 9457 responses.
 * All lifecycle rules live in the domain; all transactions in the application service.
 */
@RestController
@RequestMapping("/api/v1/incidents")
class IncidentController {

    private final IncidentService incidentService;
    private final ReferenceDataReader referenceData;

    IncidentController(IncidentService incidentService, ReferenceDataReader referenceData) {
        this.incidentService = incidentService;
        this.referenceData = referenceData;
    }

    @PostMapping
    ResponseEntity<IncidentResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Valid @RequestBody CreateIncidentRequest request) {
        CreateIncidentCommand command = new CreateIncidentCommand(
                request.service(),
                request.environment(),
                parseSeverity(request.severity()),
                emptyToNull(request.title()),
                emptyToNull(request.failureSignature()));
        Incident created = incidentService.create(command, principal.userId(), correlationId());
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + created.id()))
                .eTag(etag(created))
                .body(toResponse(created));
    }

    @GetMapping("/{id}")
    ResponseEntity<IncidentResponse> get(@PathVariable("id") UUID id) {
        Incident incident = incidentService.get(id);
        return ResponseEntity.ok().eTag(etag(incident)).body(toResponse(incident));
    }

    @PostMapping("/{id}/acknowledge")
    ResponseEntity<IncidentResponse> acknowledge(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable("id") UUID id,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Incident updated = incidentService.acknowledge(id, requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/investigate")
    ResponseEntity<IncidentResponse> investigate(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable("id") UUID id,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Incident updated = incidentService.investigate(id, requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/mitigate")
    ResponseEntity<IncidentResponse> mitigate(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable("id") UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Incident updated = incidentService.mitigate(id, requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/resolve")
    ResponseEntity<IncidentResponse> resolve(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable("id") UUID id,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Incident updated = incidentService.resolve(id, requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/close")
    ResponseEntity<IncidentResponse> close(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable("id") UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Incident updated = incidentService.close(id, requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/severity")
    ResponseEntity<IncidentResponse> changeSeverity(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable("id") UUID id,
                                                    @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                    @Valid @RequestBody ChangeSeverityRequest request) {
        Incident updated = incidentService.changeSeverity(id, parseSeverity(request.severity()),
                requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/assignment")
    ResponseEntity<IncidentResponse> assign(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @PathVariable("id") UUID id,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @Valid @RequestBody AssignIncidentRequest request) {
        UUID assigneeId = parseUuid(request.assigneeId());
        // ENGINEER (without ADMIN/INCIDENT_MANAGER) may self-assign only; the content-dependent
        // rule is enforced in the service (INV-SEC-005). URL RBAC already blocked VIEWER.
        boolean restrictedToSelf = isEngineerOnly(principal);
        Incident updated = incidentService.assign(id, assigneeId, emptyToNull(request.team()),
                requireVersion(ifMatch), principal.userId(), restrictedToSelf, correlationId());
        return ok(updated);
    }

    @DeleteMapping("/{id}/assignment")
    ResponseEntity<IncidentResponse> unassign(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable("id") UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Incident updated = incidentService.unassign(id, requireVersion(ifMatch), principal.userId(), correlationId());
        return ok(updated);
    }

    @PostMapping("/{id}/comments")
    ResponseEntity<CommentResponse> addComment(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable("id") UUID id,
                                               @Valid @RequestBody AddCommentRequest request) {
        IncidentComment comment = incidentService.addComment(id, request.body(),
                parseCategory(request.category()), principal.userId(), correlationId());
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + id + "/comments/" + comment.id()))
                .body(toCommentResponse(comment));
    }

    @GetMapping("/{id}/comments")
    ResponseEntity<List<CommentResponse>> listComments(@PathVariable("id") UUID id) {
        List<CommentResponse> body = incidentService.listComments(id).stream()
                .map(IncidentController::toCommentResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** True when the caller is an ENGINEER without ADMIN/INCIDENT_MANAGER (self-assign only). */
    private static boolean isEngineerOnly(AuthenticatedUser principal) {
        return principal.roles().contains(Role.ENGINEER)
                && !principal.roles().contains(Role.ADMIN)
                && !principal.roles().contains(Role.INCIDENT_MANAGER);
    }

    private static CommentResponse toCommentResponse(IncidentComment c) {
        return new CommentResponse(
                c.id().toString(),
                c.authorId().toString(),
                c.categoryValue().map(Enum::name).orElse(null),
                c.body(),
                c.createdAt());
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID: " + value);
        }
    }

    private static CommentCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return CommentCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown comment category: " + category);
        }
    }

    private ResponseEntity<IncidentResponse> ok(Incident incident) {
        return ResponseEntity.ok().eTag(etag(incident)).body(toResponse(incident));
    }

    /** Strong ETag of the incident version (ADR-0028): {@code "<version>"}. */
    private static String etag(Incident incident) {
        return "\"" + incident.version() + "\"";
    }

    /**
     * Parses the {@code If-Match} header into the expected version. A missing header is a
     * {@link MissingIfMatchException} (→ 428); a syntactically invalid one is treated as a
     * failed precondition (→ 412) rather than a generic 400, since it cannot match any version.
     */
    private static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new MissingIfMatchException();
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new StaleIncidentVersionException("Malformed If-Match: " + ifMatch);
        }
    }

    private IncidentResponse toResponse(Incident i) {
        String serviceKey = referenceData.findServiceKeyById(i.serviceId()).orElse(null);
        String environmentKey = referenceData.findEnvironmentKeyById(i.environmentId()).orElse(null);
        return new IncidentResponse(
                i.id().toString(),
                i.title().orElse(null),
                serviceKey,
                environmentKey,
                i.severity().name(),
                i.state().name(),
                i.currentAssigneeId().map(UUID::toString).orElse(null),
                i.createdAt(),
                i.resolvedAt().orElse(null),
                i.closedAt().orElse(null));
    }

    private static IncidentSeverity parseSeverity(String severity) {
        try {
            return IncidentSeverity.valueOf(severity);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown severity: " + severity);
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    // --- error mapping (RFC 9457) -----------------------------------------------------------

    /** No incident for the id → 404. */
    @ExceptionHandler(IncidentNotFoundException.class)
    ProblemDetail handleNotFound(IncidentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Incident not found", ex.getMessage());
    }

    /** Invalid lifecycle transition for the current state → 409 (ADR-0027). */
    @ExceptionHandler(IllegalIncidentTransitionException.class)
    ProblemDetail handleInvalidTransition(IllegalIncidentTransitionException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Invalid incident transition", ex.getMessage());
        problem.setProperty("current_state", ex.currentState().name());
        return problem;
    }

    /** Stale (or malformed) If-Match → 412 (ADR-0028). */
    @ExceptionHandler(StaleIncidentVersionException.class)
    ProblemDetail handleStale(StaleIncidentVersionException ex) {
        return problem(HttpStatus.PRECONDITION_FAILED, "Version conflict", ex.getMessage());
    }

    /** Missing If-Match on a mutation → 428 (ADR-0028). */
    @ExceptionHandler(MissingIfMatchException.class)
    ProblemDetail handleMissingIfMatch() {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "If-Match required",
                "This mutation requires an If-Match header carrying the incident's current ETag.");
    }

    /** Unknown service/environment/assignee reference → 422. */
    @ExceptionHandler(UnknownReferenceException.class)
    ProblemDetail handleUnknownReference(UnknownReferenceException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown reference", ex.getMessage());
    }

    /** Content-dependent authorization failure (e.g. ENGINEER assigning another user) → 403. */
    @ExceptionHandler(ForbiddenAssignmentException.class)
    ProblemDetail handleForbiddenAssignment(ForbiddenAssignmentException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage());
    }

    /** Invalid field value (e.g. severity) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more fields are invalid.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    /** Internal signal that {@code If-Match} was absent (→ 428). */
    private static final class MissingIfMatchException extends RuntimeException {
    }
}
