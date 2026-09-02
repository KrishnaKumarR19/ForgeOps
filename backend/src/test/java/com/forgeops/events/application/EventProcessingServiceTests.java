package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ProcessingOutcome;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for {@link EventProcessingService}: the idempotent processing effect
 * (RECEIVED → PROCESSED), the duplicate-delivery no-op (ALREADY_PROCESSED), poison-message
 * handling (NOT_FOUND → {@link NonRetryableEventProcessingException}), and propagation of
 * transient failures. Uses an in-memory repository and a no-op transaction manager; no
 * database. Synthetic data.
 */
class EventProcessingServiceTests {

    /** Executes the callback synchronously so the service's TransactionTemplate works. */
    private static final PlatformTransactionManager TX_MANAGER = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }
        public void commit(TransactionStatus s) { }
        public void rollback(TransactionStatus s) { }
    };

    /** In-memory event store recording status transitions via a conditional markProcessed. */
    private static final class InMemoryEvents implements OperationalEventRepository {
        // eventId -> status ("RECEIVED" | "PROCESSED"); absence = NOT_FOUND.
        final Map<UUID, String> status = new LinkedHashMap<>();
        final AtomicInteger markCalls = new AtomicInteger();

        @Override
        public com.forgeops.events.domain.OperationalEvent save(com.forgeops.events.domain.OperationalEvent e) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Optional<com.forgeops.events.domain.OperationalEvent> findById(UUID id) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Optional<com.forgeops.events.domain.OperationalEvent> findByClientIdAndIdempotencyKey(
                UUID clientId, String idempotencyKey) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public ProcessingOutcome markProcessed(UUID id) {
            markCalls.incrementAndGet();
            String current = status.get(id);
            if (current == null) {
                return ProcessingOutcome.NOT_FOUND;
            }
            if ("RECEIVED".equals(current)) {
                status.put(id, "PROCESSED"); // the atomic conditional transition
                return ProcessingOutcome.MARKED;
            }
            return ProcessingOutcome.ALREADY_PROCESSED;
        }
    }

    /** Repository that always throws, to prove transient failures propagate (→ retry). */
    private static final class FailingEvents implements OperationalEventRepository {
        @Override
        public com.forgeops.events.domain.OperationalEvent save(com.forgeops.events.domain.OperationalEvent e) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Optional<com.forgeops.events.domain.OperationalEvent> findById(UUID id) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Optional<com.forgeops.events.domain.OperationalEvent> findByClientIdAndIdempotencyKey(
                UUID clientId, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }
        @Override
        public ProcessingOutcome markProcessed(UUID id) {
            throw new RuntimeException("transient database failure");
        }
    }

    private static final UUID EVENT_ID = UUID.fromString("018f3000-0000-7000-8000-000000000001");

    private final InMemoryEvents events = new InMemoryEvents();
    private final EventProcessingService service = new EventProcessingService(events, TX_MANAGER);

    @Test
    void receivedEventIsMarkedProcessed() {
        events.status.put(EVENT_ID, "RECEIVED");

        ProcessingOutcome outcome = service.process(EVENT_ID);

        assertThat(outcome).isEqualTo(ProcessingOutcome.MARKED);
        assertThat(events.status.get(EVENT_ID)).isEqualTo("PROCESSED");
    }

    @Test
    void alreadyProcessedEventIsANoOp() {
        events.status.put(EVENT_ID, "PROCESSED");

        ProcessingOutcome outcome = service.process(EVENT_ID);

        assertThat(outcome).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(events.status.get(EVENT_ID)).isEqualTo("PROCESSED"); // unchanged
    }

    @Test
    void duplicateDeliveryAppliesEffectExactlyOnce() {
        events.status.put(EVENT_ID, "RECEIVED");

        ProcessingOutcome first = service.process(EVENT_ID);
        ProcessingOutcome second = service.process(EVENT_ID);
        ProcessingOutcome third = service.process(EVENT_ID);

        assertThat(first).isEqualTo(ProcessingOutcome.MARKED);
        assertThat(second).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(third).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(events.status.get(EVENT_ID)).isEqualTo("PROCESSED");
    }

    @Test
    void unknownEventIsNonRetryablePoisonMessage() {
        // No event stored → NOT_FOUND → dead-letter, not retry.
        assertThatThrownBy(() -> service.process(EVENT_ID))
                .isInstanceOf(NonRetryableEventProcessingException.class)
                .hasMessageContaining(EVENT_ID.toString());
    }

    @Test
    void transientFailurePropagatesForRetry() {
        EventProcessingService failing = new EventProcessingService(new FailingEvents(), TX_MANAGER);

        // A transient failure is NOT a NonRetryableEventProcessingException, so the consumer's
        // retry policy will retry it rather than dead-letter immediately.
        assertThatThrownBy(() -> failing.process(EVENT_ID))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NonRetryableEventProcessingException.class)
                .hasMessageContaining("transient");
    }
}
