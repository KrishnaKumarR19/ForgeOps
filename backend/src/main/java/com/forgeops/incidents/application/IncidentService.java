package com.forgeops.incidents.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeops.audit.domain.AuditActorType;
import com.forgeops.audit.domain.AuditEntry;
import com.forgeops.audit.domain.AuditEntryRepository;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentRepository;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.ReferenceDataReader;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.UnaryOperator;
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

    private final IncidentRepository incidents;
    private final AuditEntryRepository auditEntries;
    private final ReferenceDataReader referenceData;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IncidentService(IncidentRepository incidents,
                           AuditEntryRepository auditEntries,
                           ReferenceDataReader referenceData,
                           IdGenerator idGenerator,
                           Clock clock,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.incidents = incidents;
        this.auditEntries = auditEntries;
        this.referenceData = referenceData;
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
}
