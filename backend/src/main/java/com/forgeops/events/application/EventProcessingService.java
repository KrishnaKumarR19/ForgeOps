package com.forgeops.events.application;

import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ProcessingOutcome;
import com.forgeops.incidents.application.DetectionResult;
import com.forgeops.incidents.application.IncidentDetectionPort;
import com.forgeops.incidents.application.InvalidDetectionDataException;
import com.forgeops.incidents.domain.DetectionContext;
import com.forgeops.incidents.domain.IncidentSeverity;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous event-processing use case (Phase 6 Slice 3 + Phase 7 Slice 4, FR-EV-5, FR-IN-8,
 * ADR-0014/0017/0020). Applies the processing effect for an accepted operational event
 * <strong>idempotently</strong> and returns the outcome so the messaging adapter can acknowledge
 * or dead-letter accordingly.
 *
 * <p>The processing effect is now event-driven <strong>detection/correlation</strong> plus the
 * {@code RECEIVED → PROCESSED} transition, all in ONE transaction (PERSISTENCE_MODEL §18,
 * INV-INC-007, INV-EVENT-006):
 * <ol>
 *   <li>load the event; if it is not {@code RECEIVED} (already processed), this is an idempotent
 *       no-op — no detection, no incident, no association, no audit (INV-MSG-003);</li>
 *   <li>correlate the event to an active incident or create a new OPEN one via the incidents
 *       {@link IncidentDetectionPort} (SYSTEM actor);</li>
 *   <li>atomically set the event's {@code incident_id} and flip it to {@code PROCESSED} (guarded
 *       by {@code WHERE status='RECEIVED'} — the association is set exactly once).</li>
 * </ol>
 *
 * <p>Ownership (recon Option A): this application service coordinates the single transaction and
 * calls the incidents application port; it never touches incidents infrastructure, and the
 * incidents side never reads events infrastructure (a framework-free {@link DetectionContext}
 * carries the needed fields). The transaction boundary is owned here via {@link
 * TransactionTemplate}; the messaging adapter acks only after commit (INV-MSG-004).
 *
 * <p>Idempotency & concurrency (PostgreSQL-authoritative): duplicate delivery of an already-
 * PROCESSED event is a no-op; two distinct events racing to create the same incident are
 * serialized by the {@code uq_incidents_active_correlation} partial unique index — the loser's
 * transaction fails with a data-integrity violation and is retried by the consumer's bounded
 * retry, after which it correlates to the winner. A {@code NOT_FOUND} event or invalid detection
 * data is a poison message ({@link NonRetryableEventProcessingException} → DLQ).
 */
@Service
public class EventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

    private final OperationalEventRepository events;
    private final IncidentDetectionPort detection;
    private final TransactionTemplate transactionTemplate;

    public EventProcessingService(OperationalEventRepository events,
                                  IncidentDetectionPort detection,
                                  PlatformTransactionManager transactionManager) {
        this.events = events;
        this.detection = detection;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotently processes the event identified by {@code eventId}: runs detection/correlation
     * and associates + marks the event PROCESSED, atomically. Committed before this method
     * returns; the caller acknowledges only after a successful return.
     *
     * @return {@link ProcessingOutcome#MARKED} on first successful processing, or
     *         {@link ProcessingOutcome#ALREADY_PROCESSED} for a duplicate delivery (no-op)
     * @throws NonRetryableEventProcessingException if the event is unknown or its detection data
     *                                              is invalid (poison → dead-letter, not retry)
     */
    public ProcessingOutcome process(UUID eventId) {
        ProcessingOutcome outcome = transactionTemplate.execute(status -> processInTransaction(eventId));
        switch (outcome) {
            case MARKED -> log.info("Event processed (detection applied): eventId={} outcome=MARKED", eventId);
            case ALREADY_PROCESSED ->
                    log.info("Duplicate delivery ignored: eventId={} outcome=ALREADY_PROCESSED", eventId);
            case NOT_FOUND -> {
                log.warn("Unprocessable message: eventId={} outcome=NOT_FOUND (dead-lettering)", eventId);
                throw new NonRetryableEventProcessingException(
                        "No operational event exists for id " + eventId);
            }
            default -> throw new IllegalStateException("Unexpected processing outcome: " + outcome);
        }
        return outcome;
    }

    private ProcessingOutcome processInTransaction(UUID eventId) {
        OperationalEvent event = events.findById(eventId).orElse(null);
        if (event == null) {
            return ProcessingOutcome.NOT_FOUND;
        }
        // Idempotent guard: only a still-RECEIVED event runs detection (INV-MSG-003). A duplicate
        // delivery of an already-processed event does nothing further.
        if (event.status() != com.forgeops.events.domain.EventStatus.RECEIVED) {
            return ProcessingOutcome.ALREADY_PROCESSED;
        }

        DetectionContext context = new DetectionContext(
                event.id(),
                event.serviceId(),
                event.environmentId(),
                event.eventType(),
                mapSeverity(event),
                event.failureSignature().orElse(null),
                event.service(),
                event.environment(),
                event.receivedAt());

        try {
            DetectionResult result = detection.correlateOrCreate(context);
            // Set incident_id + PROCESSED, still guarded by WHERE status='RECEIVED' so the
            // association is applied exactly once even under a redelivery that raced this tx.
            return events.associateIncidentAndMarkProcessed(eventId, result.incidentId());
        } catch (InvalidDetectionDataException e) {
            // Poison: no valid signature can ever be derived — dead-letter, do not retry.
            throw new NonRetryableEventProcessingException(e.getMessage(), e);
        }
    }

    /** Maps the event's severity hint to the incident severity enum by name (shared value set). */
    private static IncidentSeverity mapSeverity(OperationalEvent event) {
        return event.severity()
                .map(s -> IncidentSeverity.valueOf(s.name()))
                .orElse(null);
    }
}
