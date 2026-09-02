package com.forgeops.incidents.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentRepository;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.IncidentState;
import com.forgeops.testsupport.PostgresTestContainer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Incident persistence integration tests against real PostgreSQL (Testcontainers), Phase 7
 * Slice 1. Verifies that {@code V4__incidents.sql} applies on top of V1–V3, that the aggregate
 * round-trips through the repository, that DB constraints (FKs, severity/state CHECK,
 * non-negative version) hold, that the deferred {@code operational_events.incident_id → incidents}
 * FK works, that the §16 indexes exist, and that incidents are not accidentally deleted.
 *
 * <p>Uses the seeded reference data (services/environments from V2). Raw-JDBC temporal params
 * are bound as {@link Timestamp} (established convention). DB isolated via TRUNCATE with correct
 * FK ordering; bootstrap admin disabled.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class IncidentPersistenceIntegrationTests {

    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Seeded reference data from V2__events.sql.
    private static final String SERVICE_ID = "018f1000-0000-7000-8000-000000000001";
    private static final String ENV_ID = "018f1001-0000-7000-8000-000000000001";
    private static final UUID SERVICE_UUID = UUID.fromString(SERVICE_ID);
    private static final UUID ENV_UUID = UUID.fromString(ENV_ID);
    private static final Instant CREATED_AT = Instant.parse("2026-03-20T00:00:00Z");

    @BeforeEach
    void setUp() {
        // FK ordering: events reference incidents; clear the reference first.
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    private UUID insertUser(String suffix) {
        UUID id = UUID.fromString("018f0000-0000-7000-8000-%012d".formatted(Integer.parseInt(suffix)));
        jdbcTemplate.update("""
                INSERT INTO users (id, username, password_hash, status, created_at)
                VALUES (?::uuid, ?, NULL, 'ACTIVE', ?)
                """, id.toString(), "user-" + suffix, Timestamp.from(CREATED_AT));
        return id;
    }

    private void insertIncidentRow(UUID id, String severity, String state, long version) {
        jdbcTemplate.update("""
                INSERT INTO incidents
                  (id, title, service_id, environment_id, failure_signature, severity, state,
                   current_assignee_id, version, created_at, resolved_at, closed_at)
                VALUES (?::uuid, 't', ?::uuid, ?::uuid, 'sig', ?, ?, NULL, ?, ?, NULL, NULL)
                """,
                id.toString(), SERVICE_ID, ENV_ID, severity, state, version,
                Timestamp.from(CREATED_AT));
    }

    private long incidentCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incidents", Long.class);
    }

    @Test
    void migrationAppliedAndTableExists() {
        // V4 applied on top of V1/V2/V3: the incidents table is queryable and empty.
        assertThat(incidentCount()).isZero();
    }

    @Test
    void savesAndLoadsIncidentRoundTrip() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000001");
        Incident incident = Incident.open(id, "Checkout 5xx", SERVICE_UUID, ENV_UUID,
                "checkout|http_5xx", IncidentSeverity.MAJOR, null, CREATED_AT);

        incidents.save(incident);
        Optional<Incident> loaded = incidents.findById(id);

        assertThat(loaded).isPresent();
        Incident got = loaded.get();
        assertThat(got.id()).isEqualTo(id);
        assertThat(got.serviceId()).isEqualTo(SERVICE_UUID);
        assertThat(got.environmentId()).isEqualTo(ENV_UUID);
        assertThat(got.severity()).isEqualTo(IncidentSeverity.MAJOR);
        assertThat(got.state()).isEqualTo(IncidentState.OPEN);
        assertThat(got.version()).isZero();
        assertThat(got.failureSignature()).contains("checkout|http_5xx");
        assertThat(got.currentAssigneeId()).isEmpty();
        assertThat(got.resolvedAt()).isEmpty();
        assertThat(got.closedAt()).isEmpty();
    }

    @Test
    void persistsNullableAssigneeAndTimestamps() {
        UUID assignee = insertUser("1");
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000002");
        Instant resolvedAt = CREATED_AT.plusSeconds(3600);
        Instant closedAt = CREATED_AT.plusSeconds(7200);
        incidents.save(new Incident(id, "t", SERVICE_UUID, ENV_UUID, "sig",
                IncidentSeverity.MINOR, IncidentState.CLOSED, assignee, 3L,
                CREATED_AT, resolvedAt, closedAt));

        Incident got = incidents.findById(id).orElseThrow();
        assertThat(got.currentAssigneeId()).contains(assignee);
        assertThat(got.version()).isEqualTo(3L);
        assertThat(got.resolvedAt()).contains(resolvedAt);
        assertThat(got.closedAt()).contains(closedAt);
    }

    @Test
    void serviceForeignKeyIsEnforced() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000003");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO incidents
                  (id, service_id, environment_id, severity, state, version, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'MAJOR', 'OPEN', 0, ?)
                """, id.toString(),
                "018f1000-0000-7000-8000-0000000000ff", ENV_ID, Timestamp.from(CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void environmentForeignKeyIsEnforced() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000004");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO incidents
                  (id, service_id, environment_id, severity, state, version, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'MAJOR', 'OPEN', 0, ?)
                """, id.toString(),
                SERVICE_ID, "018f1001-0000-7000-8000-0000000000ff", Timestamp.from(CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assigneeForeignKeyIsEnforced() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000005");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO incidents
                  (id, service_id, environment_id, severity, state, version, created_at,
                   current_assignee_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'MAJOR', 'OPEN', 0, ?, ?::uuid)
                """, id.toString(), SERVICE_ID, ENV_ID, Timestamp.from(CREATED_AT),
                "018f0000-0000-7000-8000-0000000000ff"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidStateIsRejectedByCheckConstraint() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000006");
        assertThatThrownBy(() -> insertIncidentRow(id, "MAJOR", "NOT_A_STATE", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidSeverityIsRejectedByCheckConstraint() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000007");
        assertThatThrownBy(() -> insertIncidentRow(id, "SEV0", "OPEN", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negativeVersionIsRejectedByCheckConstraint() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-000000000008");
        assertThatThrownBy(() -> insertIncidentRow(id, "MAJOR", "OPEN", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void operationalEventCanReferenceAnIncident() {
        UUID incidentId = UUID.fromString("018f5000-0000-7000-8000-000000000009");
        insertIncidentRow(incidentId, "MAJOR", "OPEN", 0);
        UUID eventId = UUID.fromString("018f6000-0000-7000-8000-000000000001");

        jdbcTemplate.update("""
                INSERT INTO operational_events
                  (id, client_id, service_id, environment_id, event_type, occurred_at,
                   received_at, payload, payload_hash, status, incident_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'http_5xx', ?, ?, ?::jsonb, ?,
                        'PROCESSED', ?::uuid)
                """,
                eventId.toString(), "018f0000-0000-7000-8000-0000000000a1", SERVICE_ID, ENV_ID,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT), "{\"a\":1}",
                "hash-" + eventId, incidentId.toString());

        String linked = jdbcTemplate.queryForObject(
                "SELECT incident_id::text FROM operational_events WHERE id = ?::uuid",
                String.class, eventId.toString());
        assertThat(linked).isEqualTo(incidentId.toString());
    }

    @Test
    void operationalEventWithUnknownIncidentIsRejected() {
        UUID eventId = UUID.fromString("018f6000-0000-7000-8000-000000000002");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO operational_events
                  (id, client_id, service_id, environment_id, event_type, occurred_at,
                   received_at, payload, payload_hash, status, incident_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'http_5xx', ?, ?, ?::jsonb, ?,
                        'PROCESSED', ?::uuid)
                """,
                eventId.toString(), "018f0000-0000-7000-8000-0000000000a1", SERVICE_ID, ENV_ID,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT), "{\"a\":1}",
                "hash-" + eventId, "018f5000-0000-7000-8000-0000000000ff"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiredIndexesExist() {
        // §16 indexes for the incidents table.
        var indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'incidents'", String.class);
        assertThat(indexNames).contains(
                "pk_incidents",
                "ix_incidents_state",
                "ix_incidents_service_env_created",
                "ix_incidents_severity_state",
                "ix_incidents_current_assignee");
    }

    @Test
    void repositorySaveOfExistingIdDoesNotDeleteOrDuplicate() {
        UUID id = UUID.fromString("018f5000-0000-7000-8000-00000000000a");
        incidents.save(Incident.open(id, "t", SERVICE_UUID, ENV_UUID, "sig",
                IncidentSeverity.MAJOR, null, CREATED_AT));
        // Re-saving the same id updates in place (upsert by PK) — never deletes, never duplicates.
        incidents.save(new Incident(id, "t2", SERVICE_UUID, ENV_UUID, "sig",
                IncidentSeverity.CRITICAL, IncidentState.ACKNOWLEDGED, null, 1L,
                CREATED_AT, null, null));

        assertThat(incidentCount()).isEqualTo(1L);
        Incident got = incidents.findById(id).orElseThrow();
        assertThat(got.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(got.state()).isEqualTo(IncidentState.ACKNOWLEDGED);
    }
}
