package com.forgeops.incidents.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeops.audit.domain.AuditActorType;
import com.forgeops.audit.domain.AuditEntry;
import com.forgeops.audit.domain.AuditEntryRepository;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.incidents.domain.CommentCategory;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentAssignment;
import com.forgeops.incidents.domain.IncidentAssignmentRepository;
import com.forgeops.incidents.domain.IncidentComment;
import com.forgeops.incidents.domain.IncidentCommentRepository;
import com.forgeops.incidents.domain.IncidentRepository;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.ReferenceDataReader;
import com.forgeops.incidents.domain.UserExistenceReader;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual incident management use case (Phase 7 Slice 2, FR-IN-1..4/6/7). Owns incident creation,
 * the lifecycle command transitions, and severity change — each an atomic unit of
 * incident-mutation + audit-entry (INV-INC-003/007, ADR-0018) written in one transaction via a
 * {@link TransactionTemplate} (the established application-layer boundary, ADR-0030).
 *
 * <p>Lifecycle rules live in the {@link Incident} aggregate; this service orchestrates the
 * transaction, the optimistic-lock compare-and-set (INV-INC-005, ADR-0028), and the audit write.
 * The optimistic guard distinguishes three outcomes deterministically: the incident is missing
 * ({@link IncidentNotFoundException} → 404), the stored version no longer matches the client's
 * {@code If-Match} ({@link StaleIncidentVersionException} → 412), or the update applied.
 *
 * <p>The audit actor is always the authenticated principal's id ({@link AuditActorType#USER});
 * no client-supplied identity is trusted (INV-SEC-005). Time is the injected {@link Clock}
 * (microsecond-truncated to match {@code timestamptz}); ids are UUID v7 via {@link IdGenerator}.
 */
@Service
public class IncidentService {

    /** Audit resource type and action names (docs give only examples; these are the chosen set). */
    private static final String RESOURCE_TYPE = "INCIDENT";
    private static final String ACTION_CREATED = "INCIDENT_CREATED";
    private static final String ACTION_STATE_CHANGED = "INCIDENT_STATE_CHANGED";
    private static final String ACTION_SEVERITY_CHANGED = "INCIDENT_SEVERITY_CHANGED";
    private static final String ACTION_ASSIGNED = "INCIDENT_ASSIGNED";
    private static final String ACTION_UNASSIGNED = "INCIDENT_UNASSIGNED";
    private static final String ACTION_COMMENTED = "INCIDENT_COMMENTED";

    private final IncidentRepository incidents;
    private final IncidentAssignmentRepository assignments;
    private final IncidentCommentRepository comments;
    private final AuditEntryRepository auditEntries;
    private final ReferenceDataReader referenceData;
    private final UserExistenceReader users;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IncidentService(IncidentRepository incidents,
                           IncidentAssignmentRepository assignments,
                           IncidentCommentRepository comments,
                           AuditEntryRepository auditEntries,
                           ReferenceDataReader referenceData,
                           UserExistenceReader users,
                           IdGenerator idGenerator,
                           Clock clock,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.incidents = incidents;
        this.assignments = assignments;
        this.comments = comments;
        this.auditEntries = auditEntries;
        this.referenceData = referenceData;
        this.users = users;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a manual incident in state {@code OPEN} and writes an {@code INCIDENT_CREATED}
     * audit entry atomically. Resolves and validates the service/environment keys; an unknown
     * key is a {@link UnknownReferenceException} (→ 422) and nothing is persisted.
     */
    public Incident create(CreateIncidentCommand command, UUID actorId, String correlationId) {
        UUID serviceId = referenceData.findServiceIdByKey(command.service())
                .orElseThrow(() -> new UnknownReferenceException("Unknown service: " + command.service()));
        UUID environmentId = referenceData.findEnvironmentIdByKey(command.environment())
                .orElseThrow(() -> new UnknownReferenceException(
                        "Unknown environment: " + command.environment()));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Incident incident = Incident.open(idGenerator.newId(), command.title(), serviceId,
                environmentId, command.failureSignature(), command.severity(), null, now);

        return transactionTemplate.execute(status -> {
            Incident saved = incidents.save(incident);
            writeAudit(actorId, ACTION_CREATED, saved.id(), null, snapshot(saved), correlationId, now);
            return saved;
        });
    }

    /** Reads an incident (no mutation, no audit). */
    public Incident get(UUID id) {
        return incidents.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    public Incident acknowledge(UUID id, long expectedVersion, UUID actorId, String correlationId) {
        return applyLifecycle(id, expectedVersion, actorId, correlationId, Incident::acknowledge);
    }

    public Incident investigate(UUID id, long expectedVersion, UUID actorId, String correlationId) {
        return applyLifecycle(id, expectedVersion, actorId, correlationId, Incident::investigate);
    }

    public Incident mitigate(UUID id, long expectedVersion, UUID actorId, String correlationId) {
        return applyLifecycle(id, expectedVersion, actorId, correlationId, Incident::mitigate);
    }

    public Incident resolve(UUID id, long expectedVersion, UUID actorId, String correlationId) {
        return applyLifecycle(id, expectedVersion, actorId, correlationId, Incident::resolve);
    }

    public Incident close(UUID id, long expectedVersion, UUID actorId, String correlationId) {
        return applyLifecycle(id, expectedVersion, actorId, correlationId, Incident::close);
    }

    /** Severity change command (audited as {@code INCIDENT_SEVERITY_CHANGED}). */
    public Incident changeSeverity(UUID id, IncidentSeverity newSeverity, long expectedVersion,
                                   UUID actorId, String correlationId) {
        return applyMutation(id, expectedVersion, actorId, correlationId, ACTION_SEVERITY_CHANGED,
                (current, now) -> current.changeSeverity(newSeverity, now));
    }

    /**
     * Assigns (or reassigns) the incident to {@code assigneeId} (Phase 7 Slice 3, FR-IN-4,
     * ADR-0021). Atomic (PERSISTENCE_MODEL §10): bump incident version + set current assignee
     * (optimistic guard), close the prior active assignment record, insert a new history record,
     * and write an audit entry — one transaction.
     *
     * <p>{@code restrictedToSelf} is true when the caller may only self-assign (ENGINEER without
     * ADMIN/INCIDENT_MANAGER); assigning anyone else then yields
     * {@link ForbiddenAssignmentException} (→ 403). The {@code assignedBy} actor is always the
     * authenticated principal (INV-SEC-005). An unknown {@code assigneeId} yields
     * {@link UnknownReferenceException} (→ 422).
     */
    public Incident assign(UUID id, UUID assigneeId, String team, long expectedVersion,
                           UUID actorId, boolean restrictedToSelf, String correlationId) {
        if (restrictedToSelf && !actorId.equals(assigneeId)) {
            throw new ForbiddenAssignmentException("You may only assign an incident to yourself");
        }
        if (!users.exists(assigneeId)) {
            throw new UnknownReferenceException("Unknown assignee: " + assigneeId);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return transactionTemplate.execute(status -> {
            Incident current = incidents.findById(id)
                    .orElseThrow(() -> new IncidentNotFoundException(id));
            Incident next = current.assignTo(assigneeId, now); // domain: version+1, not CLOSED
            if (incidents.updateAssigneeWithVersionCheck(next, expectedVersion) != 1) {
                throw new StaleIncidentVersionException(
                        "Incident " + id + " was modified concurrently (expected version "
                                + expectedVersion + ")");
            }
            assignments.closeActive(id, now); // supersede any currently-active record
            assignments.save(IncidentAssignment.active(
                    idGenerator.newId(), id, assigneeId, actorId, now, team));
            writeAudit(actorId, ACTION_ASSIGNED, id, snapshot(current), snapshot(next), correlationId, now);
            return next;
        });
    }

    /**
     * Unassigns the incident (clears the current assignee, ADR-0021). Atomic: bump version +
     * clear current assignee (optimistic guard), close the active history record, and audit.
     */
    public Incident unassign(UUID id, long expectedVersion, UUID actorId, String correlationId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return transactionTemplate.execute(status -> {
            Incident current = incidents.findById(id)
                    .orElseThrow(() -> new IncidentNotFoundException(id));
            Incident next = current.unassign(now);
            if (incidents.updateAssigneeWithVersionCheck(next, expectedVersion) != 1) {
                throw new StaleIncidentVersionException(
                        "Incident " + id + " was modified concurrently (expected version "
                                + expectedVersion + ")");
            }
            assignments.closeActive(id, now);
            writeAudit(actorId, ACTION_UNASSIGNED, id, snapshot(current), snapshot(next), correlationId, now);
            return next;
        });
    }

    /**
     * Appends an investigation note/comment to an incident (Phase 7 Slice 3, FR-IN-5,
     * INV-INC-008). Append-only; a comment does not mutate the incident (no version bump, no
     * If-Match — §11). Verifies the incident exists, inserts the comment, and writes an audit
     * entry — one transaction. Author is the authenticated principal.
     */
    public IncidentComment addComment(UUID id, String body, CommentCategory category,
                                      UUID authorId, String correlationId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return transactionTemplate.execute(status -> {
            incidents.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
            IncidentComment comment = new IncidentComment(
                    idGenerator.newId(), id, authorId, category, body, now);
            comments.save(comment);
            writeAudit(authorId, ACTION_COMMENTED, id, null, commentSnapshot(comment), correlationId, now);
            return comment;
        });
    }

    /** Lists an incident's comments (read-only). Verifies the incident exists first (→ 404). */
    public List<IncidentComment> listComments(UUID id) {
        incidents.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
        return comments.findByIncidentId(id);
    }

    // --- internal orchestration -------------------------------------------------------------

    private Incident applyLifecycle(UUID id, long expectedVersion, UUID actorId,
                                    String correlationId, java.util.function.BiFunction<Incident, Instant, Incident> transition) {
        return applyMutation(id, expectedVersion, actorId, correlationId, ACTION_STATE_CHANGED, transition);
    }

    /**
     * Shared atomic mutation flow: load, apply the domain transition (which validates the
     * current state and returns the next aggregate at {@code version + 1}), conditionally update
     * guarded by {@code expectedVersion}, and write the audit entry — all in one transaction. An
     * invalid transition throws from the domain (→ 409) before any write; a version mismatch on
     * the conditional update yields {@link StaleIncidentVersionException} (→ 412).
     */
    private Incident applyMutation(UUID id, long expectedVersion, UUID actorId, String correlationId,
                                   String action,
                                   java.util.function.BiFunction<Incident, Instant, Incident> transition) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return transactionTemplate.execute(status -> {
            Incident current = incidents.findById(id)
                    .orElseThrow(() -> new IncidentNotFoundException(id));
            // Domain validates the transition against the CURRENT state (→ 409 if illegal).
            Incident next = transition.apply(current, now);
            // Compare-and-set on the client's observed version (→ 412 if stale/lost update).
            int updated = incidents.updateWithVersionCheck(next, expectedVersion);
            if (updated != 1) {
                throw new StaleIncidentVersionException(
                        "Incident " + id + " was modified concurrently (expected version "
                                + expectedVersion + ")");
            }
            writeAudit(actorId, action, id, snapshot(current), snapshot(next), correlationId, now);
            return next;
        });
    }

    private void writeAudit(UUID actorId, String action, UUID resourceId, String oldValue,
                            String newValue, String correlationId, Instant now) {
        auditEntries.save(new AuditEntry(idGenerator.newId(), actorId, AuditActorType.USER, action,
                RESOURCE_TYPE, resourceId, now, oldValue, newValue, correlationId));
    }

    /** Compact JSON snapshot of the auditable incident fields (never secrets). */
    private String snapshot(Incident incident) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", incident.state().name());
        node.put("severity", incident.severity().name());
        node.put("version", incident.version());
        node.put("resolved_at", incident.resolvedAt().map(Instant::toString).orElse(null));
        node.put("closed_at", incident.closedAt().map(Instant::toString).orElse(null));
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize incident audit snapshot", e);
        }
    }

    /** Audit new_value for a comment: identifiers/category only, never scanning the body for secrets. */
    private String commentSnapshot(IncidentComment comment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("comment_id", comment.id().toString());
        node.put("category", comment.categoryValue().map(Enum::name).orElse(null));
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize comment audit snapshot", e);
        }
    }
}
