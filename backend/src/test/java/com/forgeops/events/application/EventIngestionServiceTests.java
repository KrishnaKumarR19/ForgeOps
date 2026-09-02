package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.events.domain.DuplicateIdempotencyKeyException;
import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.EventStatus;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ReferenceDataRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Unit tests for {@link EventIngestionService}: first-time acceptance, same-key/same-payload
 * replay, same-key/different-payload conflict, producer-scoped idempotency, server-generated
 * id, and the concurrent-duplicate race path. Uses an in-memory repository fake; no database.
 * Synthetic data.
 */
class EventIngestionServiceTests {

    private static final UUID ALICE = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");
    private final Instant now = Instant.parse("2026-02-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    /** In-memory repository enforcing the (clientId, idempotencyKey) uniqueness like the DB. */
    private static class InMemoryEvents implements OperationalEventRepository {
        final List<OperationalEvent> stored = new ArrayList<>();
        final Map<String, UUID> keyIndex = new LinkedHashMap<>(); // clientId|key -> id

        @Override
        public OperationalEvent save(OperationalEvent e) {
            if (e.idempotencyKey().isPresent()) {
                String k = e.clientId() + "|" + e.idempotencyKey().get();
                if (keyIndex.containsKey(k)) {
                    throw new DuplicateIdempotencyKeyException("dup", null);
                }
                keyIndex.put(k, e.id());
            }
            stored.add(e);
            return e;
        }

        @Override
        public Optional<OperationalEvent> findById(UUID id) {
            return stored.stream().filter(e -> e.id().equals(id)).findFirst();
        }

        @Override
        public Optional<OperationalEvent> findByClientIdAndIdempotencyKey(UUID clientId, String key) {
            if (key == null) {
                return Optional.empty();
            }
            UUID id = keyIndex.get(clientId + "|" + key);
            return id == null ? Optional.empty() : findById(id);
        }
    }

    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");

    /** Reference-data fake knowing only the "checkout" service and "prod" environment. */
    private static final ReferenceDataRepository REFERENCE_DATA = new ReferenceDataRepository() {
        @Override
        public Optional<UUID> findServiceIdByKey(String key) {
            return "checkout".equals(key) ? Optional.of(SERVICE_ID) : Optional.empty();
        }

        @Override
        public Optional<UUID> findEnvironmentIdByKey(String key) {
            return "prod".equals(key) ? Optional.of(ENV_ID) : Optional.empty();
        }
    };

    /** No-op transaction manager: runs the TransactionTemplate callback with no real tx. */
    private static final PlatformTransactionManager TX_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op
        }
    };

    private final InMemoryEvents repo = new InMemoryEvents();
    private final AtomicInteger idSeq = new AtomicInteger();
    private final IdGenerator idGenerator = () ->
            UUID.fromString("018f0000-0000-7000-8000-%012d".formatted(idSeq.incrementAndGet()));
    private final EventIngestionService service = new EventIngestionService(
            repo, REFERENCE_DATA, new PayloadCanonicalizer(), idGenerator,
            Clock.fixed(now, ZoneOffset.UTC), TX_MANAGER);

    private JsonNode payload(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    private IngestEventCommand command(UUID clientId, String key, String payloadJson) throws Exception {
        return new IngestEventCommand(clientId, key, "checkout", "prod", "http_5xx",
                EventSeverity.MAJOR, null, null, now.minusSeconds(5), payload(payloadJson));
    }

    @Test
    void firstSubmissionCreatesAndPersistsEvent() throws Exception {
        AcceptedEvent result = service.ingest(command(ALICE, "key-1", "{\"a\":1}"));

        assertThat(result.replay()).isFalse();
        assertThat(result.event().clientId()).isEqualTo(ALICE);
        assertThat(result.event().status()).isEqualTo(EventStatus.RECEIVED);
        assertThat(result.event().id()).isNotNull();
        assertThat(repo.stored).hasSize(1);
    }

    @Test
    void eventIdIsServerGenerated() throws Exception {
        AcceptedEvent result = service.ingest(command(ALICE, "key-1", "{\"a\":1}"));
        // Comes from the injected IdGenerator, not from any client input.
        assertThat(result.event().id())
                .isEqualTo(UUID.fromString("018f0000-0000-7000-8000-000000000001"));
    }

    @Test
    void sameKeySamePayloadIsReplayReturningTheSameEvent() throws Exception {
        AcceptedEvent first = service.ingest(command(ALICE, "key-1", "{\"a\":1,\"b\":2}"));
        // Same payload, different key ordering — must be treated as identical.
        AcceptedEvent second = service.ingest(command(ALICE, "key-1", "{\"b\":2,\"a\":1}"));

        assertThat(second.replay()).isTrue();
        assertThat(second.event().id()).isEqualTo(first.event().id());
        assertThat(repo.stored).hasSize(1); // no second event created
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() throws Exception {
        service.ingest(command(ALICE, "key-1", "{\"a\":1}"));

        assertThatThrownBy(() -> service.ingest(command(ALICE, "key-1", "{\"a\":999}")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(repo.stored).hasSize(1); // original unchanged, no new event
    }

    @Test
    void idempotencyIsScopedPerClient() throws Exception {
        AcceptedEvent alice = service.ingest(command(ALICE, "shared-key", "{\"a\":1}"));
        // Bob uses the SAME key value with a DIFFERENT payload — must NOT conflict with Alice.
        AcceptedEvent bob = service.ingest(command(BOB, "shared-key", "{\"a\":2}"));

        assertThat(bob.replay()).isFalse();
        assertThat(bob.event().id()).isNotEqualTo(alice.event().id());
        assertThat(bob.event().clientId()).isEqualTo(BOB);
        assertThat(repo.stored).hasSize(2);
    }

    @Test
    void submissionWithoutKeyAlwaysCreatesNewEvent() throws Exception {
        AcceptedEvent first = service.ingest(command(ALICE, null, "{\"a\":1}"));
        AcceptedEvent second = service.ingest(command(ALICE, null, "{\"a\":1}"));

        assertThat(first.replay()).isFalse();
        assertThat(second.replay()).isFalse();
        assertThat(second.event().id()).isNotEqualTo(first.event().id());
        assertThat(repo.stored).hasSize(2);
    }

    @Test
    void concurrentDuplicateRaceResolvesToReplayNotSecondEvent() throws Exception {
        // Simulate the race: pre-check misses, then save loses to a concurrent winner.
        OperationalEventRepository racingRepo = new InMemoryEvents() {
            boolean firstSave = true;
            @Override
            public OperationalEvent save(OperationalEvent e) {
                if (firstSave && e.idempotencyKey().isPresent()) {
                    firstSave = false;
                    // Insert the "winner" behind our back, then reject our insert as the DB would.
                    super.save(OperationalEvent.accepted(
                            UUID.fromString("018f0000-0000-7000-8000-0000000000ff"),
                            e.clientId(), null, e.idempotencyKey().get(), e.serviceId(),
                            e.service(), e.environmentId(), e.environment(), e.eventType(),
                            null, null, e.occurredAt(), e.receivedAt(), e.payload(), e.payloadHash()));
                    throw new DuplicateIdempotencyKeyException("race", null);
                }
                return super.save(e);
            }
        };
        EventIngestionService racing = new EventIngestionService(
                racingRepo, REFERENCE_DATA, new PayloadCanonicalizer(), idGenerator,
                Clock.fixed(now, ZoneOffset.UTC), TX_MANAGER);

        AcceptedEvent result = racing.ingest(command(ALICE, "key-1", "{\"a\":1}"));

        assertThat(result.replay()).isTrue();
        assertThat(result.event().id())
                .isEqualTo(UUID.fromString("018f0000-0000-7000-8000-0000000000ff"));
    }

    @Test
    void concurrentDuplicateRaceWithDifferentPayloadResolvesToConflict() throws Exception {
        OperationalEventRepository racingRepo = new InMemoryEvents() {
            boolean firstSave = true;
            @Override
            public OperationalEvent save(OperationalEvent e) {
                if (firstSave && e.idempotencyKey().isPresent()) {
                    firstSave = false;
                    super.save(OperationalEvent.accepted(
                            UUID.fromString("018f0000-0000-7000-8000-0000000000ee"),
                            e.clientId(), null, e.idempotencyKey().get(), e.serviceId(),
                            e.service(), e.environmentId(), e.environment(), e.eventType(),
                            null, null, e.occurredAt(), e.receivedAt(), "{\"different\":true}",
                            new PayloadCanonicalizer().hash("{\"different\":true}")));
                    throw new DuplicateIdempotencyKeyException("race", null);
                }
                return super.save(e);
            }
        };
        EventIngestionService racing = new EventIngestionService(
                racingRepo, REFERENCE_DATA, new PayloadCanonicalizer(), idGenerator,
                Clock.fixed(now, ZoneOffset.UTC), TX_MANAGER);

        assertThatThrownBy(() -> racing.ingest(command(ALICE, "key-1", "{\"a\":1}")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void invalidPayloadIsRejectedAndNothingPersisted() throws Exception {
        assertThatThrownBy(() -> service.ingest(command(ALICE, "key-1", "[1,2,3]")))
                .isInstanceOf(InvalidPayloadException.class);
        assertThat(repo.stored).isEmpty();
    }

    @Test
    void resolvesServiceAndEnvironmentKeysToReferenceIds() throws Exception {
        AcceptedEvent result = service.ingest(command(ALICE, "key-1", "{\"a\":1}"));

        assertThat(result.event().serviceId()).isEqualTo(SERVICE_ID);
        assertThat(result.event().service()).isEqualTo("checkout");
        assertThat(result.event().environmentId()).isEqualTo(ENV_ID);
        assertThat(result.event().environment()).isEqualTo("prod");
    }

    @Test
    void unknownServiceIsRejectedAsUnknownReferenceAndNothingPersisted() throws Exception {
        IngestEventCommand cmd = new IngestEventCommand(ALICE, "key-1", "no-such-service", "prod",
                "http_5xx", EventSeverity.MAJOR, null, null, now.minusSeconds(5), payload("{\"a\":1}"));

        assertThatThrownBy(() -> service.ingest(cmd))
                .isInstanceOf(UnknownReferenceException.class);
        assertThat(repo.stored).isEmpty();
    }

    @Test
    void unknownEnvironmentIsRejectedAsUnknownReferenceAndNothingPersisted() throws Exception {
        IngestEventCommand cmd = new IngestEventCommand(ALICE, "key-1", "checkout", "no-such-env",
                "http_5xx", EventSeverity.MAJOR, null, null, now.minusSeconds(5), payload("{\"a\":1}"));

        assertThatThrownBy(() -> service.ingest(cmd))
                .isInstanceOf(UnknownReferenceException.class);
        assertThat(repo.stored).isEmpty();
    }
}
