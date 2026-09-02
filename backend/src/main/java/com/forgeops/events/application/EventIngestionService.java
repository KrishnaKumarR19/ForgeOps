package com.forgeops.events.application;

import com.forgeops.common.id.IdGenerator;
import com.forgeops.events.domain.DuplicateIdempotencyKeyException;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.events.domain.ReferenceDataRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts operational events (FR-EV-1..4). Acceptance is atomic from the application's view
 * (INV-EVENT-006): validation, the idempotency decision, and persistence happen inside one
 * transaction, so no partially-accepted event is ever returned as successful. This slice
 * persists only the event; the Phase 6 outbox insert will join this same transaction later.
 *
 * <p>Idempotency is scoped to the authenticated client (ADR-0025). For a given
 * {@code (clientId, idempotencyKey)}:
 * <ul>
 *   <li><b>Case A</b> — no existing event: create, persist, return it (not a replay).</li>
 *   <li><b>Cases B/D/E</b> — an event exists with the same {@code payload_hash}: return the
 *       existing event unchanged (idempotent replay); no second event is created.</li>
 *   <li><b>Case C</b> — an event exists with a different {@code payload_hash}: reject with
 *       {@link IdempotencyConflictException} (→ {@code 409}); the original is untouched.</li>
 * </ul>
 *
 * <p>Before the idempotency decision, the submitted {@code service}/{@code environment} keys
 * are resolved against known reference data; an unknown key is rejected with
 * {@link UnknownReferenceException} (→ {@code 422}) and no event is persisted.
 *
 * <p>Correctness rests on PostgreSQL: the {@code (client_id, idempotency_key)} uniqueness
 * constraint is authoritative. The pre-check below is an optimization; a concurrent duplicate
 * that slips past it is caught as {@link DuplicateIdempotencyKeyException} on save, after
 * which the winning event is re-read and the same replay-vs-conflict rule is applied. Two
 * concurrent identical requests therefore yield exactly one accepted event.
 *
 * <p>Atomic event + outbox (Phase 6 Slice 1, INV-OUTBOX-001, INV-EVENT-006, ADR-0013): the
 * new-event path writes the event and exactly one {@code PENDING} outbox message inside a
 * single {@link TransactionTemplate} transaction — both commit or both roll back, so a durable
 * accepted event always has its outbox record and a failure leaves neither. The transaction is
 * isolated so a {@code (client_id, idempotency_key)} unique-violation rollback does not poison
 * the recovery re-read, which runs in a fresh transaction. Replay and conflict paths never
 * create an outbox message. No publishing happens here — that is a later slice.
 */
@Service
public class EventIngestionService {

    private final OperationalEventRepository events;
    private final OutboxMessageRepository outbox;
    private final OutboxMessageFactory outboxMessageFactory;
    private final ReferenceDataRepository referenceData;
    private final PayloadCanonicalizer canonicalizer;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public EventIngestionService(OperationalEventRepository events,
                                 OutboxMessageRepository outbox,
                                 OutboxMessageFactory outboxMessageFactory,
                                 ReferenceDataRepository referenceData,
                                 PayloadCanonicalizer canonicalizer,
                                 IdGenerator idGenerator,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.events = events;
        this.outbox = outbox;
        this.outboxMessageFactory = outboxMessageFactory;
        this.referenceData = referenceData;
        this.canonicalizer = canonicalizer;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AcceptedEvent ingest(IngestEventCommand command) {
        // Resolve service/environment keys against known reference data (API_CONTRACTS §6);
        // an unknown key is a 422, not a persisted event (INV-SEC-003 — validate at the
        // trust boundary before affecting business state).
        UUID serviceId = referenceData.findServiceIdByKey(command.service())
                .orElseThrow(() -> new UnknownReferenceException(
                        "Unknown service: " + command.service()));
        UUID environmentId = referenceData.findEnvironmentIdByKey(command.environment())
                .orElseThrow(() -> new UnknownReferenceException(
                        "Unknown environment: " + command.environment()));

        String canonicalPayload = canonicalizer.canonicalize(command.payload());
        String payloadHash = canonicalizer.hash(canonicalPayload);

        // Idempotency pre-check: recognize a retry before attempting an insert.
        if (command.idempotencyKey() != null) {
            Optional<OperationalEvent> existing = findExisting(command);
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), payloadHash);
            }
        }

        OperationalEvent toCreate = build(command, serviceId, environmentId, canonicalPayload, payloadHash);
        try {
            // Atomic event + outbox write (INV-OUTBOX-001, INV-EVENT-006, ADR-0013): both the
            // event and its single PENDING outbox message are persisted in ONE transaction, so
            // they commit together or roll back together — there is never a durable accepted
            // event without its outbox record. Keeping this in its own transaction also isolates
            // a (client_id, idempotency_key) unique-violation rollback so it does not poison the
            // recovery read below. Only this new-event path creates an outbox message; replay
            // and conflict paths never do.
            OperationalEvent saved = transactionTemplate.execute(status -> {
                OperationalEvent persisted = events.save(toCreate);
                outbox.save(outboxMessageFactory.forAcceptedEvent(persisted));
                return persisted;
            });
            return new AcceptedEvent(saved, false);
        } catch (DuplicateIdempotencyKeyException race) {
            // A concurrent request won the (client_id, idempotency_key) race between our
            // pre-check and insert. The failed insert transaction has rolled back; re-read the
            // winner in a FRESH transaction and apply the same rule (same payload = replay,
            // different = conflict). Never create a second event.
            OperationalEvent winner = findExisting(command).orElseThrow(() -> race);
            return resolveExisting(winner, payloadHash);
        }
    }

    /** Looks up an existing event for the command's client + key in its own transaction. */
    private Optional<OperationalEvent> findExisting(IngestEventCommand command) {
        return transactionTemplate.execute(status ->
                events.findByClientIdAndIdempotencyKey(command.clientId(), command.idempotencyKey()));
    }

    private AcceptedEvent resolveExisting(OperationalEvent existing, String payloadHash) {
        if (existing.payloadHash().equals(payloadHash)) {
            return new AcceptedEvent(existing, true); // replay (Case B/D/E)
        }
        throw new IdempotencyConflictException(
                "Idempotency key already used with a different payload"); // Case C
    }

    private OperationalEvent build(IngestEventCommand command, UUID serviceId, UUID environmentId,
                                   String canonicalPayload, String payloadHash) {
        UUID id = idGenerator.newId();
        // Truncate to microseconds: PostgreSQL timestamptz has microsecond precision, so a
        // nanosecond JVM instant would be rounded on storage and read back differently. Using
        // the stored precision here keeps the acceptance response byte-identical to what a
        // later read (e.g. an idempotent replay resolved from the DB) returns.
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return OperationalEvent.accepted(
                id,
                command.clientId(),
                command.producerEventId(),
                command.idempotencyKey(),
                serviceId,
                command.service(),
                environmentId,
                command.environment(),
                command.eventType(),
                command.severity(),
                command.failureSignature(),
                command.occurredAt(),
                receivedAt,
                canonicalPayload,
                payloadHash);
    }
}
