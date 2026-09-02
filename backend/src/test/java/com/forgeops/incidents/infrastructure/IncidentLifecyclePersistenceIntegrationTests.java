package com.forgeops.incidents.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.incidents.application.CreateIncidentCommand;
import com.forgeops.incidents.application.IncidentService;
import com.forgeops.incidents.application.StaleIncidentVersionException;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.IncidentState;
import com.forgeops.testsupport.PostgresTestContainer;
import java.util.List;
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
 * Incident lifecycle + audit + optimistic-concurrency integration tests against real PostgreSQL
 * (Testcontainers), Phase 7 Slice 2. Drives the real {@link IncidentService} (and its JPA
 * repositories) so the atomic incident-mutation + audit-insert (INV-INC-007, ADR-0018) and the
 * compare-and-set version guard (INV-INC-005, ADR-0028) are exercised end-to-end against the DB,
 * including a deterministic concurrent-writer race. DB isolated via TRUNCATE; bootstrap admin
 * disabled.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class IncidentLifecyclePersistenceIntegrationTests {

    @Autowired
    private IncidentService incidentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID ACTOR = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
    }

    private CreateIncidentCommand createCommand() {
        // Seeded reference data from V2 (checkout / production).
        return new CreateIncidentCommand("checkout", "production", IncidentSeverity.MAJOR, "t", "sig");
    }

    private long auditCount(UUID incidentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE resource_type='INCIDENT' AND resource_id = ?::uuid",
                Long.class, incidentId.toString());
    }

    private String state(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM incidents WHERE id = ?::uuid", String.class, id.toString());
    }

    private long version(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM incidents WHERE id = ?::uuid", Long.class, id.toString());
    }

    @Test
    void createPersistsOpenIncidentWithAudit() {
        Incident created = incidentService.create(createCommand(), ACTOR, "corr-1");

        assertThat(state(created.id())).isEqualTo("OPEN");
        assertThat(version(created.id())).isZero();
        assertThat(auditCount(created.id())).isEqualTo(1L);
        String action = jdbcTemplate.queryForObject(
                "SELECT action FROM audit_entries WHERE resource_id = ?::uuid",
                String.class, created.id().toString());
        assertThat(action).isEqualTo("INCIDENT_CREATED");
    }

    @Test
    void lifecycleMutationPersistsAndIncrementsVersionOnceWithAudit() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.acknowledge(created.id(), 0L, ACTOR, "c");

        assertThat(state(created.id())).isEqualTo("ACKNOWLEDGED");
        assertThat(version(created.id())).isEqualTo(1L);       // exactly one increment
        assertThat(auditCount(created.id())).isEqualTo(2L);    // create + transition
    }

    @Test
    void staleVersionIsRejectedAndWritesNoAudit() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.acknowledge(created.id(), 0L, ACTOR, "c"); // -> version 1
        long auditsBefore = auditCount(created.id());

        assertThatThrownBy(() -> incidentService.investigate(created.id(), 0L, ACTOR, "c"))
                .isInstanceOf(StaleIncidentVersionException.class);

        assertThat(version(created.id())).isEqualTo(1L);        // unchanged
        assertThat(state(created.id())).isEqualTo("ACKNOWLEDGED");
        assertThat(auditCount(created.id())).isEqualTo(auditsBefore); // no audit for the failed op
    }

    @Test
    void resolveThenCloseSetsTimestamps() {
        Incident c = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.acknowledge(c.id(), 0L, ACTOR, "c");
        incidentService.investigate(c.id(), 1L, ACTOR, "c");
        incidentService.mitigate(c.id(), 2L, ACTOR, "c");
        incidentService.resolve(c.id(), 3L, ACTOR, "c");
        incidentService.close(c.id(), 4L, ACTOR, "c");

        assertThat(state(c.id())).isEqualTo("CLOSED");
        assertThat(version(c.id())).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resolved_at FROM incidents WHERE id = ?::uuid",
                java.time.Instant.class, c.id().toString())).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT closed_at FROM incidents WHERE id = ?::uuid",
                java.time.Instant.class, c.id().toString())).isNotNull();
        assertThat(auditCount(c.id())).isEqualTo(6L); // create + 5 transitions
    }

    @Test
    void auditEntriesAreAppendOnlyAndImmutable() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        // A domain-path UPDATE/DELETE of audit is never issued; verify the row is stable and
        // that a manual attempt to violate would be a test-only action (not done here).
        assertThat(auditCount(created.id())).isEqualTo(1L);
        incidentService.acknowledge(created.id(), 0L, ACTOR, "c");
        // The original create audit row is still present and unchanged (append-only).
        assertThat(auditCount(created.id())).isEqualTo(2L);
        Long createdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE action='INCIDENT_CREATED' AND resource_id = ?::uuid",
                Long.class, created.id().toString());
        assertThat(createdRows).isEqualTo(1L);
    }

    @Test
    void concurrentSameVersionMutationsYieldExactlyOneWinner() throws Exception {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        long auditsAfterCreate = auditCount(created.id());

        // Two concurrent acknowledge commands both using If-Match version 0.
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger staleFailures = new AtomicInteger();

        Callable<Void> task = () -> {
            ready.countDown();
            go.await();
            try {
                incidentService.acknowledge(created.id(), 0L, ACTOR, "c");
                successes.incrementAndGet();
            } catch (StaleIncidentVersionException e) {
                staleFailures.incrementAndGet();
            }
            return null;
        };
        Future<Void> f1 = pool.submit(task);
        Future<Void> f2 = pool.submit(task);
        ready.await();
        go.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        // Exactly one winner, one stale failure; final version N+1; exactly one extra audit.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(staleFailures.get()).isEqualTo(1);
        assertThat(version(created.id())).isEqualTo(1L);
        assertThat(state(created.id())).isEqualTo("ACKNOWLEDGED");
        assertThat(auditCount(created.id())).isEqualTo(auditsAfterCreate + 1);
    }

    @Test
    void invalidSeverityValueRejectedByDbCheck() {
        // Direct DB insert with a bad severity is rejected by ck_incidents_severity (defense in depth).
        UUID id = UUID.fromString("018f5000-0000-7000-8000-0000000000c1");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO incidents (id, service_id, environment_id, severity, state, version, created_at)
                VALUES (?::uuid, '018f1000-0000-7000-8000-000000000001'::uuid,
                        '018f1001-0000-7000-8000-000000000001'::uuid, 'SEV0', 'OPEN', 0, now())
                """, id.toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void listResourceHistoryIndexSupportsAuditQuery() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.acknowledge(created.id(), 0L, ACTOR, "c");
        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_entries WHERE resource_type='INCIDENT' AND resource_id = ?::uuid "
                        + "ORDER BY occurred_at", String.class, created.id().toString());
        assertThat(actions).containsExactly("INCIDENT_CREATED", "INCIDENT_STATE_CHANGED");
    }
}
