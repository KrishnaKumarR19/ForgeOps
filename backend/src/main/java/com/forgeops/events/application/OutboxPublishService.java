package com.forgeops.events.application;

import com.forgeops.events.domain.MessageBroker;
import com.forgeops.events.domain.MessagePublishException;
import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox publisher use case (Phase 6 Slice 2, ADR-0013 steps 4–7, ADR-0022): claim due
 * {@code PENDING} outbox rows, publish each to the broker, and mark the outcome — all within
 * one transaction.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Claim due {@code PENDING} rows with {@code FOR UPDATE SKIP LOCKED} so concurrent
 *       publishers never claim the same row (INV-OUTBOX, PERSISTENCE_MODEL §14).</li>
 *   <li>A publish is successful only when the broker confirms acceptance; then the row is
 *       marked {@code PUBLISHED} (INV-OUTBOX-005 — the mark is a non-critical optimization).</li>
 *   <li>A failed publish leaves the row {@code PENDING} (retryable, INV-OUTBOX-003) with
 *       {@code attempts+1}, a backoff {@code next_attempt_at}, and a bounded {@code last_error}.
 *       A single failure does not abort the rest of the batch.</li>
 *   <li>Delivery is at-least-once (INV-MSG-001): a crash after broker acceptance but before
 *       this transaction commits leaves the row {@code PENDING}, so it may be published again
 *       later — an accepted duplicate handled by future idempotent consumers.</li>
 * </ul>
 *
 * <p>The transaction boundary is owned here in the application layer (BACKEND_ARCHITECTURE §8)
 * via {@link TransactionTemplate}. Time comes from the injected {@link Clock}. Logs carry
 * identifying metadata only — never the payload, credentials, or secrets.
 */
@Service
public class OutboxPublishService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublishService.class);
    /** Bounded diagnostic stored in {@code last_error}; full detail goes to logs only. */
    private static final int MAX_LAST_ERROR_LENGTH = 500;

    private final OutboxMessageRepository outbox;
    private final MessageBroker broker;
    private final BackoffPolicy backoffPolicy;
    private final OutboxPublisherProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublishService(OutboxMessageRepository outbox,
                                MessageBroker broker,
                                BackoffPolicy backoffPolicy,
                                OutboxPublisherProperties properties,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.broker = broker;
        this.backoffPolicy = backoffPolicy;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs one publish cycle: claim a batch and publish it, in a single transaction. Returns
     * the number of messages confirmed published in this cycle.
     */
    public int publishBatch() {
        Integer published = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<OutboxMessage> batch = outbox.claimPending(properties.batchSize(), now);
            int successes = 0;
            for (OutboxMessage message : batch) {
                if (publishOne(message, now)) {
                    successes++;
                }
            }
            return successes;
        });
        return published == null ? 0 : published;
    }

    private boolean publishOne(OutboxMessage message, Instant now) {
        try {
            broker.publish(message);
            outbox.markPublished(message.id(), now);
            log.info("Outbox message published: outboxId={} aggregateId={} messageType={} attempts={}",
                    message.id(), message.aggregateId(), message.messageType(), message.attempts());
            return true;
        } catch (MessagePublishException e) {
            int attempts = message.attempts() + 1;
            Duration delay = backoffPolicy.delayForAttempt(attempts);
            Instant nextAttemptAt = now.plus(delay);
            outbox.recordFailure(message.id(), attempts, nextAttemptAt, boundedError(e));
            log.warn("Outbox publish failed, will retry: outboxId={} aggregateId={} messageType={} "
                            + "attempts={} nextAttemptAt={} reason={}",
                    message.id(), message.aggregateId(), message.messageType(), attempts,
                    nextAttemptAt, safeReason(e));
            return false;
        }
    }

    /** Bounded, single-line diagnostic for the DB (never the payload or secrets). */
    private static String boundedError(MessagePublishException e) {
        String reason = safeReason(e);
        return reason.length() > MAX_LAST_ERROR_LENGTH
                ? reason.substring(0, MAX_LAST_ERROR_LENGTH) : reason;
    }

    private static String safeReason(MessagePublishException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.replaceAll("\\s+", " ").trim();
    }
}
