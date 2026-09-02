package com.forgeops.incidents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.audit.domain.AuditActorType;
import com.forgeops.audit.domain.AuditEntry;
import com.forgeops.audit.domain.AuditEntryRepository;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.incidents.domain.DetectionContext;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentRepository;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.IncidentState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IncidentDetectionService}: create-when-no-match (severity default MINOR,
 * generated title, SYSTEM audit INCIDENT_CREATED), correlate-when-match (SYSTEM audit
 * INCIDENT_EVENT_CORRELATED, no new incident), and poison on blank signature. In-memory fakes;
 * the DB-enforced concurrency/window behavior is covered by the Testcontainers tests.
 */
class IncidentDetectionServiceTests {

    private static final UUID EVENT_ID = UUID.fromString("018f3000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");
    private final Instant now = Instant.parse("2026-03-20T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    /** In-memory incident store; findActiveMatch returns a preset match if configured. */
    private static final class InMemoryIncidents implements IncidentRepository {
        final List<Incident> saved = new ArrayList<>();
        Incident presetMatch;

        @Override
        public Incident save(Incident incident) {
            saved.add(incident);
            return incident;
        }
        @Override
        public Optional<Incident> findById(UUID id) {
            return saved.stream().filter(i -> i.id().equals(id)).findFirst();
        }
        @Override
        public int updateWithVersionCheck(Incident next, long expectedVersion) {
            return 1;
        }
        @Override
        public int updateAssigneeWithVersionCheck(Incident next, long expectedVersion) {
            return 1;
        }
        @Override
        public Optional<Incident> findActiveMatch(UUID s, UUID e, String sig, Instant r, Duration w) {
            return Optional.ofNullable(presetMatch);
        }
    }

    private static final class InMemoryAudit implements AuditEntryRepository {
        final List<AuditEntry> entries = new ArrayList<>();
        @Override
        public AuditEntry save(AuditEntry entry) {
            entries.add(entry);
            return entry;
        }
    }

    private final AtomicInteger idSeq = new AtomicInteger();
    private final IdGenerator idGenerator = () ->
            UUID.fromString("018f5000-0000-7000-8000-%012d".formatted(idSeq.incrementAndGet()));
    private final InMemoryIncidents incidents = new InMemoryIncidents();
    private final InMemoryAudit audit = new InMemoryAudit();
    private final DetectionProperties properties = new DetectionProperties(Duration.ofMinutes(30));
    private final IncidentDetectionService service = new IncidentDetectionService(
            incidents, audit, properties, idGenerator, clock, new ObjectMapper());

    private DetectionContext context(IncidentSeverity severity, String signature) {
        return new DetectionContext(EVENT_ID, SERVICE_ID, ENV_ID, "http_5xx", severity, signature,
                "checkout", "production", now);
    }

    @Test
    void createsNewIncidentWhenNoMatch() {
        DetectionResult result = service.correlateOrCreate(context(IncidentSeverity.MAJOR, "boom"));

        assertThat(result.created()).isTrue();
        assertThat(incidents.saved).hasSize(1);
        Incident created = incidents.saved.get(0);
        assertThat(created.state()).isEqualTo(IncidentState.OPEN);
        assertThat(created.severity()).isEqualTo(IncidentSeverity.MAJOR);
        assertThat(created.failureSignature()).contains("boom");
        assertThat(created.title()).contains("checkout/production: http_5xx");
        assertThat(created.currentAssigneeId()).isEmpty();
        // SYSTEM audit, actor_id NULL, action INCIDENT_CREATED.
        AuditEntry a = audit.entries.get(0);
        assertThat(a.action()).isEqualTo("INCIDENT_CREATED");
        assertThat(a.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(a.actorId()).isNull();
    }

    @Test
    void defaultsSeverityToMinorWhenAbsent() {
        service.correlateOrCreate(context(null, "boom"));
        assertThat(incidents.saved.get(0).severity()).isEqualTo(IncidentSeverity.MINOR);
    }

    @Test
    void correlatesToExistingActiveIncidentWithoutCreating() {
        incidents.presetMatch = Incident.detected(
                UUID.fromString("018f5000-0000-7000-8000-0000000000aa"), "existing", SERVICE_ID,
                ENV_ID, "boom", IncidentSeverity.MAJOR, now.minusSeconds(60));

        DetectionResult result = service.correlateOrCreate(context(IncidentSeverity.MAJOR, "boom"));

        assertThat(result.created()).isFalse();
        assertThat(result.incidentId()).isEqualTo(incidents.presetMatch.id());
        assertThat(incidents.saved).isEmpty(); // no new incident
        AuditEntry a = audit.entries.get(0);
        assertThat(a.action()).isEqualTo("INCIDENT_EVENT_CORRELATED");
        assertThat(a.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(a.actorId()).isNull();
    }

    @Test
    void poisonWhenNoUsableSignature() {
        assertThatThrownBy(() -> service.correlateOrCreate(
                new DetectionContext(EVENT_ID, SERVICE_ID, ENV_ID, "  ", IncidentSeverity.MAJOR,
                        "  ", "checkout", "production", now)))
                .isInstanceOf(InvalidDetectionDataException.class);
    }
}
