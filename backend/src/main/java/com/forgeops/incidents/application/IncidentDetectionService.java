package com.forgeops.incidents.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeops.audit.domain.AuditActorType;
import com.forgeops.audit.domain.AuditEntry;
import com.forgeops.audit.domain.AuditEntryRepository;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.incidents.domain.DetectionContext;
import com.forgeops.incidents.domain.FailureSignatureNormalizer;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentRepository;
import com.forgeops.incidents.domain.IncidentSeverity;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deterministic, rule-based event-driven detection/correlation (Phase 7 Slice 4, ADR-0017,
 * ADR-0020, ratified v1 contract). Implements {@link IncidentDetectionPort}: given a processed
 * event's context, it correlates the event to an existing active incident or creates a new OPEN
 * incident, and writes a SYSTEM audit entry.
 *
 * <p><strong>No own transaction.</strong> This service runs inside the caller's (events
 * consumer) transaction so the incident create/correlate + audit commit atomically with the
 * event's {@code incident_id} association and {@code RECEIVED → PROCESSED} transition
 * (PERSISTENCE_MODEL §18, INV-INC-007). Time is the injected {@link Clock}; ids are UUID v7.
 *
 * <p>Correlation key = {@code (service_id, environment_id, normalized failure signature)};
 * active states = OPEN/ACKNOWLEDGED/INVESTIGATING/MITIGATED; sliding 30-minute window on the
 * event's {@code received_at}; newest active match wins. Concurrency: a partial unique index
 * ({@code uq_incidents_active_correlation}) guarantees at most one active incident per key — a
 * losing concurrent INSERT surfaces as a data-integrity violation that the caller retries,
 * after which this service finds and attaches to the winner. Detection is SYSTEM-actor
 * (audit {@code actor_id} is NULL — no fake user).
 */
@Service
public class IncidentDetectionService implements IncidentDetectionPort {

    private static final Logger log = LoggerFactory.getLogger(IncidentDetectionService.class);
    private static final String RESOURCE_TYPE = "INCIDENT";
    private static final String ACTION_CREATED = "INCIDENT_CREATED";
    private static final String ACTION_CORRELATED = "INCIDENT_EVENT_CORRELATED";
    /** Severity used when the event carries no severity hint (ratified default). */
    private static final IncidentSeverity DEFAULT_SEVERITY = IncidentSeverity.MINOR;
    private static final int MAX_TITLE_LENGTH = 300;

    private final IncidentRepository incidents;
    private final AuditEntryRepository auditEntries;
    private final DetectionProperties properties;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IncidentDetectionService(IncidentRepository incidents,
                                    AuditEntryRepository auditEntries,
                                    DetectionProperties properties,
                                    IdGenerator idGenerator,
                                    Clock clock,
                                    ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.auditEntries = auditEntries;
        this.properties = properties;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public DetectionResult correlateOrCreate(DetectionContext ctx) {
        String signature = FailureSignatureNormalizer
                .normalize(ctx.failureSignature(), ctx.eventType())
                .orElseThrow(() -> new InvalidDetectionDataException(
                        "Event " + ctx.eventId() + " has no usable failure signature or event type"));

        Optional<Incident> match = incidents.findActiveMatch(
                ctx.serviceId(), ctx.environmentId(), signature, ctx.receivedAt(),
                properties.correlationWindow());

        if (match.isPresent()) {
            Incident incident = match.get();
            writeAudit(ACTION_CORRELATED, incident.id(), correlationNewValue(ctx.eventId(), incident.id()));
            log.info("Event correlated to incident: eventId={} incidentId={}", ctx.eventId(), incident.id());
            return new DetectionResult(incident.id(), false);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        IncidentSeverity severity = ctx.severity() == null ? DEFAULT_SEVERITY : ctx.severity();
        Incident created = Incident.detected(idGenerator.newId(), buildTitle(ctx), ctx.serviceId(),
                ctx.environmentId(), signature, severity, now);
        // May throw DataIntegrityViolationException on the active-correlation unique index if a
        // concurrent event created the incident first; the caller retries and correlates.
        incidents.save(created);
        writeAudit(ACTION_CREATED, created.id(), createdNewValue(created));
        log.info("Incident created by detection: eventId={} incidentId={} severity={}",
                ctx.eventId(), created.id(), severity);
        return new DetectionResult(created.id(), true);
    }

    /** Bounded, deterministic title from safe reference context: {@code "<service>/<env>: <type>"}. */
    private static String buildTitle(DetectionContext ctx) {
        String service = ctx.serviceKey() != null ? ctx.serviceKey() : ctx.serviceId().toString();
        String environment = ctx.environmentKey() != null
                ? ctx.environmentKey() : ctx.environmentId().toString();
        String eventType = ctx.eventType() == null ? "event" : ctx.eventType();
        String title = service + "/" + environment + ": " + eventType;
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private void writeAudit(String action, UUID incidentId, String newValue) {
        auditEntries.save(new AuditEntry(idGenerator.newId(), null, AuditActorType.SYSTEM, action,
                RESOURCE_TYPE, incidentId, clock.instant().truncatedTo(ChronoUnit.MICROS),
                null, newValue, null));
    }

    private String createdNewValue(Incident incident) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", incident.state().name());
        node.put("severity", incident.severity().name());
        node.put("failure_signature", incident.failureSignature().orElse(null));
        return serialize(node);
    }

    private String correlationNewValue(UUID eventId, UUID incidentId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("event_id", eventId.toString());
        node.put("incident_id", incidentId.toString());
        return serialize(node);
    }

    private String serialize(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize detection audit snapshot", e);
        }
    }
}
