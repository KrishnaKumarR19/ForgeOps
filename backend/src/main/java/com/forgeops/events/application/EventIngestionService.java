package com.forgeops.events.application;

import com.forgeops.common.id.IdGenerator;
import com.forgeops.events.domain.DuplicateIdempotencyKeyException;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ReferenceDataRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class EventIngestionService {

    private final OperationalEventRepository events;
    private final ReferenceDataRepository referenceData;
    private final PayloadCanonicalizer canonicalizer;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public EventIngestionService(OperationalEventRepository events,
                                 ReferenceDataRepository referenceData,
                                 PayloadCanonicalizer canonicalizer,
                                 IdGenerator idGenerator,
                                 Clock clock) {
        this.events = events;
        this.referenceData = referenceData;
        this.canonicalizer = canonicalizer;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Transactional
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
            Optional<OperationalEvent> existing = events.findByClientIdAndIdempotencyKey(
                    command.clientId(), command.idempotencyKey());
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), payloadHash);
            }
        }

        OperationalEvent toCreate = build(command, serviceId, environmentId, canonicalPayload, payloadHash);
        try {
            return new AcceptedEvent(events.save(toCreate), false);
        } catch (DuplicateIdempotencyKeyException race) {
            // A concurrent request won the (client_id, idempotency_key) race between our
            // pre-check and insert. Re-read the winner and apply the same rule: same payload
            // is a replay, different payload is a conflict. Never create a second event.
            OperationalEvent winner = events
                    .findByClientIdAndIdempotencyKey(command.clientId(), command.idempotencyKey())
                    .orElseThrow(() -> race);
            return resolveExisting(winner, payloadHash);
        }
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
