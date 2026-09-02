package com.forgeops.incidents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.audit.domain.AuditEntry;
import com.forgeops.audit.domain.AuditEntryRepository;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.incidents.domain.IllegalIncidentTransitionException;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentRepository;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.incidents.domain.IncidentState;
import com.forgeops.incidents.domain.ReferenceDataReader;
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
 * Unit tests for {@link IncidentService}: manual creation, lifecycle commands, severity change,
 * not-found (404), invalid transition (409-mapped domain exception), stale version (412-mapped),
 * atomic audit creation, and no-audit-on-failure. In-memory fakes; no database. Synthetic data.
 */
class IncidentServiceTests {

    private static final UUID ACTOR = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");
    private final Instant now = Instant.parse("2026-03-20T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private static final PlatformTransactionManager TX_MANAGER = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }
        public void commit(TransactionStatus s) { }
        public void rollback(TransactionStatus s) { }
    };

    /** In-memory incident store implementing the compare-and-set version guard. */
    private static final class InMemoryIncidents implements IncidentRepository {
        final Map<UUID, Incident> byId = new LinkedHashMap<>();

        @Override
        public Incident save(Incident incident) {
            byId.put(incident.id(), incident);
            return incident;
        }
        @Override
        public Optional<Incident> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override
        public int updateWithVersionCheck(Incident next, long expectedVersion) {
            Incident current = byId.get(next.id());
            if (current == null || current.version() != expectedVersion) {
                return 0; // stale or absent
            }
            byId.put(next.id(), next);
            return 1;
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

    private final ReferenceDataReader referenceData = new ReferenceDataReader() {
        public Optional<UUID> findServiceIdByKey(String key) {
            return "checkout".equals(key) ? Optional.of(SERVICE_ID) : Optional.empty();
        }
        public Optional<UUID> findEnvironmentIdByKey(String key) {
            return "production".equals(key) ? Optional.of(ENV_ID) : Optional.empty();
        }
        public Optional<String> findServiceKeyById(UUID id) {
            return Optional.of("checkout");
        }
        public Optional<String> findEnvironmentKeyById(UUID id) {
            return Optional.of("production");
        }
    };

    private final AtomicInteger idSeq = new AtomicInteger();
    private final IdGenerator idGenerator = () ->
            UUID.fromString("018f5000-0000-7000-8000-%012d".formatted(idSeq.incrementAndGet()));

    private final InMemoryIncidents incidents = new InMemoryIncidents();
    private final InMemoryAudit audit = new InMemoryAudit();
    private final IncidentService service = new IncidentService(
            incidents, audit, referenceData, idGenerator, clock, new ObjectMapper(), TX_MANAGER);

    private CreateIncidentCommand createCommand() {
        return new CreateIncidentCommand("checkout", "production", IncidentSeverity.MAJOR, "t", "sig");
    }

    @Test
    void createOpensIncidentAndWritesAudit() {
        Incident created = service.create(createCommand(), ACTOR, "corr-1");

        assertThat(created.state()).isEqualTo(IncidentState.OPEN);
        assertThat(created.version()).isZero();
        assertThat(created.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(incidents.byId).containsKey(created.id());
        assertThat(audit.entries).hasSize(1);
        AuditEntry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo("INCIDENT_CREATED");
        assertThat(entry.actorId()).isEqualTo(ACTOR);        // actor from principal, not request
        assertThat(entry.resourceId()).isEqualTo(created.id());
        assertThat(entry.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void createWithUnknownServiceIsRejectedAndPersistsNothing() {
        CreateIncidentCommand bad = new CreateIncidentCommand("nope", "production",
                IncidentSeverity.MAJOR, "t", null);
        assertThatThrownBy(() -> service.create(bad, ACTOR, "c"))
                .isInstanceOf(UnknownReferenceException.class);
        assertThat(incidents.byId).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    void acknowledgeTransitionsAndAuditsAtomically() {
        Incident created = service.create(createCommand(), ACTOR, "c");
        Incident acked = service.acknowledge(created.id(), 0L, ACTOR, "c2");

        assertThat(acked.state()).isEqualTo(IncidentState.ACKNOWLEDGED);
        assertThat(acked.version()).isEqualTo(1L);
        assertThat(incidents.byId.get(created.id()).version()).isEqualTo(1L);
        assertThat(audit.entries).hasSize(2); // create + state change
        assertThat(audit.entries.get(1).action()).isEqualTo("INCIDENT_STATE_CHANGED");
    }

    @Test
    void notFoundThrows() {
        assertThatThrownBy(() -> service.acknowledge(
                UUID.fromString("018f5000-0000-7000-8000-0000000000ff"), 0L, ACTOR, "c"))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    @Test
    void invalidTransitionThrowsAndWritesNoAudit() {
        Incident created = service.create(createCommand(), ACTOR, "c");
        int auditsAfterCreate = audit.entries.size();

        assertThatThrownBy(() -> service.resolve(created.id(), 0L, ACTOR, "c")) // OPEN cannot resolve
                .isInstanceOf(IllegalIncidentTransitionException.class);
        assertThat(audit.entries).hasSize(auditsAfterCreate); // no new audit
        assertThat(incidents.byId.get(created.id()).state()).isEqualTo(IncidentState.OPEN); // unchanged
    }

    @Test
    void staleVersionThrowsAndWritesNoAudit() {
        Incident created = service.create(createCommand(), ACTOR, "c");
        service.acknowledge(created.id(), 0L, ACTOR, "c"); // now version 1
        int auditsBefore = audit.entries.size();

        // Second command using the STALE version 0 → 412.
        assertThatThrownBy(() -> service.investigate(created.id(), 0L, ACTOR, "c"))
                .isInstanceOf(StaleIncidentVersionException.class);
        assertThat(audit.entries).hasSize(auditsBefore);
        assertThat(incidents.byId.get(created.id()).version()).isEqualTo(1L); // unchanged
    }

    @Test
    void severityChangeAuditsWithSeverityAction() {
        Incident created = service.create(createCommand(), ACTOR, "c");
        Incident changed = service.changeSeverity(created.id(), IncidentSeverity.CRITICAL, 0L, ACTOR, "c");

        assertThat(changed.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(changed.version()).isEqualTo(1L);
        assertThat(audit.entries.get(audit.entries.size() - 1).action()).isEqualTo("INCIDENT_SEVERITY_CHANGED");
    }

    @Test
    void fullHappyPathToClosed() {
        Incident c = service.create(createCommand(), ACTOR, "c");
        service.acknowledge(c.id(), 0L, ACTOR, "c");
        service.investigate(c.id(), 1L, ACTOR, "c");
        service.mitigate(c.id(), 2L, ACTOR, "c");
        Incident resolved = service.resolve(c.id(), 3L, ACTOR, "c");
        assertThat(resolved.resolvedAt()).contains(now);
        Incident closed = service.close(c.id(), 4L, ACTOR, "c");
        assertThat(closed.state()).isEqualTo(IncidentState.CLOSED);
        assertThat(closed.closedAt()).contains(now);
        assertThat(closed.version()).isEqualTo(5L);
    }
}
