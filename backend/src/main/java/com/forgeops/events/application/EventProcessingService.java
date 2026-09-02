package com.forgeops.events.application;

import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ProcessingOutcome;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous event-processing use case (Phase 6 Slice 3, FR-EV-5 consumer side, ADR-0014).
 * Applies the processing effect for an accepted operational event <strong>idempotently</strong>
 * and returns the outcome so the messaging adapter can acknowledge or dead-letter accordingly.
 *
 * <p>The processing effect in this slice is the {@code RECEIVED → PROCESSED} state transition
 * (PERSISTENCE_MODEL §events; the {@code status} column). Incident detection/correlation is a
 * later phase (Phase 7) and is deliberately out of scope here.
 *
 * <p>Idempotency (INV-MSG-003, FR-RL-3/10): the effect is a single conditional
 * {@code UPDATE ... WHERE status = 'RECEIVED'} executed by
 * {@link OperationalEventRepository#markProcessed(UUID)}. At-least-once delivery (INV-MSG-001)
 * means the same message may arrive more than once; the first delivery transitions the row and
 * every subsequent delivery observes {@code ALREADY_PROCESSED} and does nothing further. There
 * is no check-then-update window, so concurrent duplicate deliveries cannot both apply the
 * effect. PostgreSQL is the sole source of truth — no in-memory or broker-side dedup.
 *
 * <p>Transaction boundary (INV-MSG-004): the effect runs inside a {@link TransactionTemplate}
 * transaction owned here in the application layer. The messaging adapter acknowledges the
 * message only after this method returns successfully — i.e. only after the DB commit — so a
 * crash before commit leaves the row {@code RECEIVED} and the message is redelivered.
 *
 * <p>A {@code NOT_FOUND} event is a poison message (no event will ever appear for that id) and
 * is reported as {@link NonRetryableEventProcessingException} so the adapter dead-letters it
 * immediately rather than retrying (INV-MSG-006). Transient infrastructure failures propagate
 * as-is and are retried by the adapter (FR-RL-4).
 */
@Service
public class EventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

    private final OperationalEventRepository events;
    private final TransactionTemplate transactionTemplate;

    public EventProcessingService(OperationalEventRepository events,
                                  PlatformTransactionManager transactionManager) {
        this.events = events;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotently applies the processing effect for the event identified by {@code eventId}.
     * The state change is committed before this method returns; the caller acknowledges only
     * after a successful return.
     *
     * @param eventId the accepted event's server-generated resource id (from the message body)
     * @return {@link ProcessingOutcome#MARKED} on the first successful processing, or
     *         {@link ProcessingOutcome#ALREADY_PROCESSED} for a duplicate delivery (no-op)
     * @throws NonRetryableEventProcessingException if no event exists for {@code eventId}
     *                                              (poison message → dead-letter, not retry)
     */
    public ProcessingOutcome process(UUID eventId) {
        ProcessingOutcome outcome = transactionTemplate.execute(status -> events.markProcessed(eventId));
        switch (outcome) {
            case MARKED -> log.info("Event processed: eventId={} outcome=MARKED", eventId);
            case ALREADY_PROCESSED ->
                    log.info("Duplicate delivery ignored: eventId={} outcome=ALREADY_PROCESSED", eventId);
            case NOT_FOUND -> {
                // No such event will ever exist; retrying cannot help. Dead-letter it.
                log.warn("Unprocessable message: eventId={} outcome=NOT_FOUND (dead-lettering)", eventId);
                throw new NonRetryableEventProcessingException(
                        "No operational event exists for id " + eventId);
            }
            default -> throw new IllegalStateException("Unexpected processing outcome: " + outcome);
        }
        return outcome;
    }
}
