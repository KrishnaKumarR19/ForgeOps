package com.forgeops.incidents.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.events.application.EventProcessingService;
import com.forgeops.events.application.NonRetryableEventProcessingException;
import com.forgeops.testsupport.PostgresTestContainer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Event-driven detection/correlation integration tests against real PostgreSQL (Testcontainers),
 * Phase 7 Slice 4. Drives the real {@link EventProcessingService} (real detection service + JPA
 * repositories) so the whole atomic unit — correlate/create incident + SYSTEM audit + event
 * {@code incident_id} association + {@code RECEIVED → PROCESSED} — is exercised end-to-end,
 * including the partial-unique-index concurrency safeguard and a deterministic concurrent race.
 *
 * <p>Ratified v1 contract: 30-minute sliding window on {@code received_at}; correlation key
 * (service, environment, normalized signature); active states OPEN/ACKNOWLEDGED/INVESTIGATING/
 * MITIGATED; newest-wins; severity default MINOR; SYSTEM audit (actor_id NULL). DB isolated via
 * TRUNCATE; bootstrap admin disabled. Correlation window shortened is unnecessary — tests place
 * incidents relative to event received_at.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class IncidentDetectionCorrelationIntegrationTests {

    @Autowired
    private EventProcessingService processingService;
    @Autowired
    private com.forgeops.incidents.domain.IncidentRepository incidentRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Seeded reference data (V2).
    private static final String SERVICE_A = "018f1000-0000-7000-8000-000000000001"; // checkout
    private static final String SERVICE_B = "018f1000-0000-7000-8000-000000000002"; // payments
    private static final String ENV_A = "018f1001-0000-7000-8000-000000000001";     // production
    private static final String ENV_B = "018f1001-0000-7000-8000-000000000002";     // staging
    private static final UUID CLIENT = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    // Anchored to the real clock: detection CREATES incidents stamped with the injected system
    // Clock (created_at ~= now). Correlating to a detection-created incident (e.g. the concurrency
    // race) requires the event's received_at to be at/after that created_at within the window, so
    // events must be anchored near now — a hardcoded past date would place every event before the
    // winner's created_at and make correlation impossible. Pre-inserted incidents use baseNow-
    // relative timestamps, so all window boundaries remain exact.
    private final Instant baseNow = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
    }

    private UUID insertReceivedEvent(String serviceId, String envId, String signature, Instant receivedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operational_events
                  (id, client_id, service_id, environment_id, event_type, severity, failure_signature,
                   occurred_at, received_at, payload, payload_hash, status)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'http_5xx', 'MAJOR', ?, ?, ?, ?::jsonb, ?, 'RECEIVED')
                """,
                id.toString(), CLIENT.toString(), serviceId, envId, signature,
                Timestamp.from(receivedAt.minusSeconds(1)), Timestamp.from(receivedAt),
                "{\"a\":1}", "hash-" + id);
        return id;
    }

    private UUID insertIncident(String serviceId, String envId, String signature, String state,
                                Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO incidents
                  (id, title, service_id, environment_id, failure_signature, severity, state,
                   current_assignee_id, version, created_at, resolved_at, closed_at)
                VALUES (?::uuid, 't', ?::uuid, ?::uuid, ?, 'MAJOR', ?, NULL, 0, ?, NULL, NULL)
                """,
                id.toString(), serviceId, envId, signature, state, Timestamp.from(createdAt));
        return id;
    }

    private String eventStatus(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM operational_events WHERE id = ?::uuid", String.class, eventId.toString());
    }

    private String eventIncidentId(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT incident_id::text FROM operational_events WHERE id = ?::uuid",
                String.class, eventId.toString());
    }

    private long incidentCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incidents", Long.class);
    }

    private long activeIncidentCount(String serviceId, String signature) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incidents WHERE service_id = ?::uuid AND failure_signature = ? "
                        + "AND state IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED')",
                Long.class, serviceId, signature);
    }

    // ----- create / correlate --------------------------------------------------

    @Test
    void eventWithNoMatchCreatesNewIncident() {
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);

        assertThat(incidentCount()).isEqualTo(1L);
        assertThat(eventStatus(eventId)).isEqualTo("PROCESSED");
        assertThat(eventIncidentId(eventId)).isNotNull();
    }

    @Test
    void eventWithinWindowCorrelatesToExistingActiveIncident() {
        UUID incidentId = insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow.minus(Duration.ofMinutes(10)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);

        processingService.process(eventId);

        assertThat(incidentCount()).isEqualTo(1L); // no new incident
        assertThat(eventIncidentId(eventId)).isEqualTo(incidentId.toString());
    }

    @Test
    void eventOutsideWindowCreatesNewIncident() {
        // An incident whose created_at is 45m before the event's received_at is outside the 30m
        // window and must NOT be matched. Because uq_incidents_active_correlation permits at most
        // ONE active incident per correlation key, this stale incident has since been RESOLVED
        // (it aged out) — so the temporal miss legitimately drives creation of a new active
        // incident without two active rows ever sharing the key. Detection must create a new one.
        UUID stale = insertIncident(SERVICE_A, ENV_A, "boom", "RESOLVED", baseNow.minus(Duration.ofMinutes(45)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);

        processingService.process(eventId);

        assertThat(incidentCount()).isEqualTo(2L);
        assertThat(activeIncidentCount(SERVICE_A, "boom")).isEqualTo(1L);
        assertThat(eventIncidentId(eventId)).isNotEqualTo(stale.toString()); // not the stale one
    }

    @Test
    void activeIncidentOutsideWindowIsNotMatchedByFindActiveMatch() {
        // Direct temporal-predicate check at the repository level: an ACTIVE incident created
        // outside the window is not returned by findActiveMatch (no second active row is created,
        // so the uniqueness invariant is respected).
        insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow.minus(Duration.ofMinutes(45)));
        Optional<com.forgeops.incidents.domain.Incident> match = incidentRepository.findActiveMatch(
                UUID.fromString(SERVICE_A), UUID.fromString(ENV_A), "boom", baseNow, Duration.ofMinutes(30));
        assertThat(match).isEmpty();
    }

    @Test
    void eventExactlyAtWindowBoundaryCorrelates() {
        // incident created exactly 30m before received_at → created_at == windowStart → matches.
        UUID incidentId = insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow.minus(Duration.ofMinutes(30)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);

        processingService.process(eventId);

        assertThat(incidentCount()).isEqualTo(1L);
        assertThat(eventIncidentId(eventId)).isEqualTo(incidentId.toString());
    }

    @Test
    void futureCreatedIncidentIsNotMatchedByFindActiveMatch() {
        // An incident created AFTER the event's received_at must not match the temporal predicate
        // (no future incidents). Asserted at the repository level so we never hold two active
        // incidents with the same correlation key (uq_incidents_active_correlation invariant):
        // the future incident stays the only active row for the key.
        insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow.plus(Duration.ofMinutes(5)));
        Optional<com.forgeops.incidents.domain.Incident> match = incidentRepository.findActiveMatch(
                UUID.fromString(SERVICE_A), UUID.fromString(ENV_A), "boom", baseNow, Duration.ofMinutes(30));
        assertThat(match).isEmpty();
        assertThat(activeIncidentCount(SERVICE_A, "boom")).isEqualTo(1L);
    }

    @Test
    void differentServiceDoesNotMatch() {
        insertIncident(SERVICE_B, ENV_A, "boom", "OPEN", baseNow.minus(Duration.ofMinutes(5)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        assertThat(incidentCount()).isEqualTo(2L);
    }

    @Test
    void differentEnvironmentDoesNotMatch() {
        insertIncident(SERVICE_A, ENV_B, "boom", "OPEN", baseNow.minus(Duration.ofMinutes(5)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        assertThat(incidentCount()).isEqualTo(2L);
    }

    @Test
    void differentSignatureDoesNotMatch() {
        insertIncident(SERVICE_A, ENV_A, "other", "OPEN", baseNow.minus(Duration.ofMinutes(5)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        assertThat(incidentCount()).isEqualTo(2L);
    }

    @Test
    void resolvedIncidentDoesNotCorrelate() {
        insertIncident(SERVICE_A, ENV_A, "boom", "RESOLVED", baseNow.minus(Duration.ofMinutes(5)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        assertThat(incidentCount()).isEqualTo(2L); // new incident, resolved one untouched
    }

    @Test
    void closedIncidentDoesNotCorrelate() {
        insertIncident(SERVICE_A, ENV_A, "boom", "CLOSED", baseNow.minus(Duration.ofMinutes(5)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        assertThat(incidentCount()).isEqualTo(2L);
    }

    // ----- concurrency / idempotency -------------------------------------------

    @Test
    void partialUniqueIndexPreventsTwoActiveIncidentsForSameKey() {
        insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow);
        // A direct second active insert with the same key must be rejected by the unique index.
        assertThatThrownBy(() -> insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow.plusSeconds(1)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDistinctEventsCreateExactlyOneIncidentAndBothAssociate() throws Exception {
        // The winner CREATEs the incident with created_at from the real Clock (~= now = baseNow).
        // The loser must correlate to it, which requires the events' received_at to be at/after
        // that created_at (findActiveMatch needs created_at <= received_at). Anchor the events a
        // minute ahead of baseNow so correlation is deterministic regardless of the exact commit
        // instant, still far inside the 30-minute window.
        Instant receivedAt = baseNow.plus(Duration.ofMinutes(1));
        UUID e1 = insertReceivedEvent(SERVICE_A, ENV_A, "boom", receivedAt);
        UUID e2 = insertReceivedEvent(SERVICE_A, ENV_A, "boom", receivedAt);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        // Each task mirrors the consumer's bounded retry: the unique-conflict loser retries and
        // then correlates to the winner (the real consumer retries via Spring AMQP).
        Callable<Void> t1 = () -> { runWithRetry(ready, go, e1, errors); return null; };
        Callable<Void> t2 = () -> { runWithRetry(ready, go, e2, errors); return null; };
        Future<Void> f1 = pool.submit(t1);
        Future<Void> f2 = pool.submit(t2);
        ready.await();
        go.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        assertThat(errors.get()).isZero();
        // Exactly one active incident for the key; both events associated to it.
        assertThat(activeIncidentCount(SERVICE_A, "boom")).isEqualTo(1L);
        assertThat(incidentCount()).isEqualTo(1L);
        assertThat(eventStatus(e1)).isEqualTo("PROCESSED");
        assertThat(eventStatus(e2)).isEqualTo("PROCESSED");
        assertThat(eventIncidentId(e1)).isEqualTo(eventIncidentId(e2)); // same incident
    }

    private void runWithRetry(CountDownLatch ready, CountDownLatch go, UUID eventId, AtomicInteger errors) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                processingService.process(eventId);
                return;
            } catch (RuntimeException ex) {
                if (attempt == 4) {
                    errors.incrementAndGet();
                }
            }
        }
    }

    @Test
    void duplicateDeliveryIsIdempotent() {
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        String firstIncident = eventIncidentId(eventId);

        // Second delivery: event already PROCESSED → no-op.
        processingService.process(eventId);

        assertThat(incidentCount()).isEqualTo(1L);
        assertThat(eventIncidentId(eventId)).isEqualTo(firstIncident); // unchanged
        Long createdAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE action='INCIDENT_CREATED'", Long.class);
        assertThat(createdAudits).isEqualTo(1L); // one detection audit only
    }

    @Test
    void eventBecomesProcessedWithIncidentIdSetExactlyOnce() {
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);
        assertThat(eventStatus(eventId)).isEqualTo("PROCESSED");
        assertThat(eventIncidentId(eventId)).isNotNull();
    }

    @Test
    void unknownEventIsNonRetryable() {
        UUID ghost = UUID.fromString("018f6000-0000-7000-8000-0000000000ff");
        assertThatThrownBy(() -> processingService.process(ghost))
                .isInstanceOf(NonRetryableEventProcessingException.class);
        assertThat(incidentCount()).isZero();
    }

    // ----- audit ---------------------------------------------------------------

    @Test
    void detectionAuditIsSystemActorWithNullActorId() {
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT actor_type, actor_id, action FROM audit_entries WHERE resource_type='INCIDENT'");
        assertThat(rows).isNotEmpty();
        for (var row : rows) {
            assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
            assertThat(row.get("actor_id")).isNull();
        }
    }

    @Test
    void correlationAuditActionRecorded() {
        insertIncident(SERVICE_A, ENV_A, "boom", "OPEN", baseNow.minus(Duration.ofMinutes(5)));
        UUID eventId = insertReceivedEvent(SERVICE_A, ENV_A, "boom", baseNow);
        processingService.process(eventId);

        Long correlated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE action='INCIDENT_EVENT_CORRELATED'", Long.class);
        assertThat(correlated).isEqualTo(1L);
    }
}
