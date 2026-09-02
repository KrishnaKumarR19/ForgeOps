package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.EventStatus;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ProcessingOutcome;
import com.forgeops.incidents.application.DetectionResult;
import com.forgeops.incidents.application.IncidentDetectionPort;
import com.forgeops.incidents.application.InvalidDetectionDataException;
import com.forgeops.incidents.domain.DetectionContext;
import java.time.Instant;
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
 * Unit tests for {@link EventProcessingService} with detection integrated (Phase 7 Slice 4):
 * a RECEIVED event runs detection/correlation then is associated + marked PROCESSED; a duplicate
 * delivery of an already-PROCESSED event is a no-op (no detection); an unknown event is a poison
 * message (NOT_FOUND → {@link NonRetryableEventProcessingException}); invalid detection data is
 * also poison; and a transient detection failure propagates for retry. In-memory fakes; no DB.
 */
class EventProcessingServiceTests {

    private static final PlatformTransactionManager TX_MANAGER = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }
        public void commit(TransactionStatus s) { }
        public void rollback(TransactionStatus s) { }
    };

    private static final UUID EVENT_ID = UUID.fromString("018f3000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");
    private static final UUID INCIDENT_ID = UUID.fromString("018f5000-0000-7000-8000-000000000009");
    private static final Instant NOW = Instant.parse("2026-03-20T00:00:00Z");

    /** In-memory event store; models the conditional associate+process guard. */
    private static final class InMemoryEvents implements OperationalEventRepository {
        final Map<UUID, OperationalEvent> byId = new LinkedHashMap<>();
        UUID associatedIncidentId;

        @Override
        public OperationalEvent save(OperationalEvent e) {
            byId.put(e.id(), e);
            return e;
        }
        @Override
        public Optional<OperationalEvent> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override
        public Optional<OperationalEvent> findByClientIdAndIdempotencyKey(UUID c, String k) {
            return Optional.empty();
        }
        @Override
        public ProcessingOutcome markProcessed(UUID id) {
            throw new UnsupportedOperationException("detection path uses associateIncidentAndMarkProcessed");
        }
        @Override
        public ProcessingOutcome associateIncidentAndMarkProcessed(UUID id, UUID incidentId) {
            OperationalEvent e = byId.get(id);
            if (e == null) {
                return ProcessingOutcome.NOT_FOUND;
            }
            if (e.status() != EventStatus.RECEIVED) {
                return ProcessingOutcome.ALREADY_PROCESSED;
            }
            associatedIncidentId = incidentId;
            byId.put(id, processed(e, incidentId));
            return ProcessingOutcome.MARKED;
        }
    }

    /** Detection port fake recording invocations and returning a fixed incident. */
    private static final class FakeDetection implements IncidentDetectionPort {
        final AtomicInteger calls = new AtomicInteger();
        DetectionContext lastContext;
        @Override
        public DetectionResult correlateOrCreate(DetectionContext context) {
            calls.incrementAndGet();
            lastContext = context;
            return new DetectionResult(INCIDENT_ID, true);
        }
    }

    private static OperationalEvent received(EventStatus status) {
        return new OperationalEvent(EVENT_ID, UUID.randomUUID(), null, null, SERVICE_ID, "checkout",
                ENV_ID, "production", "http_5xx", EventSeverity.MAJOR, "sig", NOW, NOW,
                "{\"a\":1}", "hash", status, null);
    }

    private static OperationalEvent processed(OperationalEvent e, UUID incidentId) {
        return new OperationalEvent(e.id(), e.clientId(), e.producerEventId().orElse(null),
                e.idempotencyKey().orElse(null), e.serviceId(), e.service(), e.environmentId(),
                e.environment(), e.eventType(), e.severity().orElse(null),
                e.failureSignature().orElse(null), e.occurredAt(), e.receivedAt(), e.payload(),
                e.payloadHash(), EventStatus.PROCESSED, incidentId);
    }

    private final InMemoryEvents events = new InMemoryEvents();
    private final FakeDetection detection = new FakeDetection();
    private final EventProcessingService service =
            new EventProcessingService(events, detection, TX_MANAGER);

    @Test
    void receivedEventRunsDetectionAndIsAssociatedAndProcessed() {
        events.save(received(EventStatus.RECEIVED));

        ProcessingOutcome outcome = service.process(EVENT_ID);

        assertThat(outcome).isEqualTo(ProcessingOutcome.MARKED);
        assertThat(detection.calls.get()).isEqualTo(1);
        assertThat(events.associatedIncidentId).isEqualTo(INCIDENT_ID);
        assertThat(events.byId.get(EVENT_ID).status()).isEqualTo(EventStatus.PROCESSED);
        assertThat(events.byId.get(EVENT_ID).incidentId()).contains(INCIDENT_ID);
        // DetectionContext carried the event's correlation fields.
        assertThat(detection.lastContext.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(detection.lastContext.failureSignature()).isEqualTo("sig");
    }

    @Test
    void alreadyProcessedEventIsANoOpWithNoDetection() {
        events.save(received(EventStatus.PROCESSED));

        ProcessingOutcome outcome = service.process(EVENT_ID);

        assertThat(outcome).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(detection.calls.get()).isZero(); // no detection for a duplicate delivery
    }

    @Test
    void unknownEventIsNonRetryablePoison() {
        assertThatThrownBy(() -> service.process(EVENT_ID))
                .isInstanceOf(NonRetryableEventProcessingException.class);
        assertThat(detection.calls.get()).isZero();
    }

    @Test
    void invalidDetectionDataIsNonRetryablePoison() {
        events.save(received(EventStatus.RECEIVED));
        IncidentDetectionPort poison = context -> {
            throw new InvalidDetectionDataException("no usable signature");
        };
        EventProcessingService svc = new EventProcessingService(events, poison, TX_MANAGER);

        assertThatThrownBy(() -> svc.process(EVENT_ID))
                .isInstanceOf(NonRetryableEventProcessingException.class);
        assertThat(events.byId.get(EVENT_ID).status()).isEqualTo(EventStatus.RECEIVED); // unchanged
    }

    @Test
    void transientDetectionFailurePropagatesForRetry() {
        events.save(received(EventStatus.RECEIVED));
        IncidentDetectionPort failing = context -> {
            throw new RuntimeException("transient detection failure");
        };
        EventProcessingService svc = new EventProcessingService(events, failing, TX_MANAGER);

        assertThatThrownBy(() -> svc.process(EVENT_ID))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NonRetryableEventProcessingException.class)
                .hasMessageContaining("transient");
    }
}
