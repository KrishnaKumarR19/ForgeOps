package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ProcessingOutcome;
import com.forgeops.testsupport.PostgresTestContainer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repository-level tests for the idempotent {@code markProcessed} conditional update against
 * real PostgreSQL (Testcontainers). Proves the {@code RECEIVED → PROCESSED} transition, the
 * duplicate no-op ({@code ALREADY_PROCESSED}), the {@code NOT_FOUND} outcome, and that the
 * conditional {@code WHERE status = 'RECEIVED'} guard never re-applies to a processed row.
 *
 * <p>Like the Slice 2 outbox tests, the {@code @Modifying} update is exercised inside an
 * explicit {@link TransactionTemplate} (a bare call has no active transaction and JPA would
 * reject the update). DB isolated via TRUNCATE; bootstrap admin disabled.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class EventProcessingRepositoryIntegrationTests {

    @Autowired
    private OperationalEventRepository events;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // Seeded reference data from V2__events.sql.
    private static final String SERVICE_ID = "018f1000-0000-7000-8000-000000000001";
    private static final String ENV_ID = "018f1001-0000-7000-8000-000000000001";
    private static final UUID CLIENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_messages CASCADE");
    }

    private void insertEvent(UUID id, String status) {
        jdbcTemplate.update("""
                INSERT INTO operational_events
                  (id, client_id, service_id, environment_id, event_type, occurred_at,
                   received_at, payload, payload_hash, status)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'http_5xx', ?, ?, ?::jsonb, ?, ?)
                """,
                id.toString(), CLIENT_ID.toString(), SERVICE_ID, ENV_ID,
                Timestamp.from(Instant.parse("2026-03-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-03-01T00:00:01Z")),
                "{\"a\":1}", "hash-" + id, status);
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM operational_events WHERE id = ?::uuid", String.class, id.toString());
    }

    private ProcessingOutcome markProcessedInTx(UUID id) {
        return new TransactionTemplate(transactionManager)
                .execute(txStatus -> events.markProcessed(id));
    }

    @Test
    void receivedEventTransitionsToProcessed() {
        UUID id = UUID.fromString("018f3100-0000-7000-8000-000000000001");
        insertEvent(id, "RECEIVED");

        ProcessingOutcome outcome = markProcessedInTx(id);

        assertThat(outcome).isEqualTo(ProcessingOutcome.MARKED);
        assertThat(status(id)).isEqualTo("PROCESSED");
    }

    @Test
    void secondMarkIsAlreadyProcessedNoOp() {
        UUID id = UUID.fromString("018f3100-0000-7000-8000-000000000002");
        insertEvent(id, "RECEIVED");

        assertThat(markProcessedInTx(id)).isEqualTo(ProcessingOutcome.MARKED);
        // A duplicate delivery: the conditional WHERE status='RECEIVED' matches nothing now.
        assertThat(markProcessedInTx(id)).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(status(id)).isEqualTo("PROCESSED"); // still processed, applied exactly once
    }

    @Test
    void alreadyProcessedRowReportsAlreadyProcessed() {
        UUID id = UUID.fromString("018f3100-0000-7000-8000-000000000003");
        insertEvent(id, "PROCESSED");

        assertThat(markProcessedInTx(id)).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(status(id)).isEqualTo("PROCESSED");
    }

    @Test
    void unknownEventReportsNotFound() {
        UUID id = UUID.fromString("018f3100-0000-7000-8000-0000000000ff");

        assertThat(markProcessedInTx(id)).isEqualTo(ProcessingOutcome.NOT_FOUND);
    }
}
