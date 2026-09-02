package com.forgeops.incidents.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.incidents.application.CreateIncidentCommand;
import com.forgeops.incidents.application.ForbiddenAssignmentException;
import com.forgeops.incidents.application.IncidentService;
import com.forgeops.incidents.application.StaleIncidentVersionException;
import com.forgeops.incidents.application.UnknownReferenceException;
import com.forgeops.incidents.domain.CommentCategory;
import com.forgeops.incidents.domain.Incident;
import com.forgeops.incidents.domain.IncidentComment;
import com.forgeops.incidents.domain.IncidentSeverity;
import com.forgeops.testsupport.PostgresTestContainer;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Assignment + comment persistence integration tests against real PostgreSQL (Testcontainers),
 * Phase 7 Slice 3. Drives the real {@link IncidentService} so assignment (version-bumping
 * mutation + append-only history + audit, ADR-0021) and comments (append-only + audit) are
 * exercised end-to-end, including a deterministic concurrent-assign race. Real users are
 * provisioned (FK integrity). DB isolated via TRUNCATE (FK order); bootstrap admin disabled.
 */
@SpringBootTest(properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class IncidentAssignmentCommentPersistenceIntegrationTests {

    @Autowired
    private IncidentService incidentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID ACTOR = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID ASSIGNEE = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incident_comments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incident_assignments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        insertUser(ACTOR, "actor");
        insertUser(ASSIGNEE, "assignee");
    }

    private void insertUser(UUID id, String username) {
        jdbcTemplate.update("""
                INSERT INTO users (id, username, password_hash, status, created_at)
                VALUES (?::uuid, ?, NULL, 'ACTIVE', now())
                """, id.toString(), username);
    }

    private CreateIncidentCommand createCommand() {
        return new CreateIncidentCommand("checkout", "production", IncidentSeverity.MAJOR, "t", "sig");
    }

    private long version(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM incidents WHERE id = ?::uuid", Long.class, id.toString());
    }

    private String currentAssignee(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT current_assignee_id::text FROM incidents WHERE id = ?::uuid",
                String.class, id.toString());
    }

    private long assignmentCount(UUID incidentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident_assignments WHERE incident_id = ?::uuid",
                Long.class, incidentId.toString());
    }

    private long activeAssignmentCount(UUID incidentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident_assignments WHERE incident_id = ?::uuid "
                        + "AND unassigned_at IS NULL", Long.class, incidentId.toString());
    }

    @Test
    void assignPersistsCurrentAssigneeHistoryAndAudit() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.assign(created.id(), ASSIGNEE, "sre", 0L, ACTOR, false, "c");

        assertThat(currentAssignee(created.id())).isEqualTo(ASSIGNEE.toString());
        assertThat(version(created.id())).isEqualTo(1L);
        assertThat(assignmentCount(created.id())).isEqualTo(1L);
        assertThat(activeAssignmentCount(created.id())).isEqualTo(1L);
        Long assignAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE action='INCIDENT_ASSIGNED' AND resource_id = ?::uuid",
                Long.class, created.id().toString());
        assertThat(assignAudits).isEqualTo(1L);
    }

    @Test
    void reassignAppendsHistoryAndClosesPrior() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.assign(created.id(), ASSIGNEE, null, 0L, ACTOR, false, "c");
        incidentService.assign(created.id(), ACTOR, null, 1L, ACTOR, false, "c");

        assertThat(assignmentCount(created.id())).isEqualTo(2L);       // append-only history
        assertThat(activeAssignmentCount(created.id())).isEqualTo(1L); // only latest active
        assertThat(currentAssignee(created.id())).isEqualTo(ACTOR.toString());
        assertThat(version(created.id())).isEqualTo(2L);
    }

    @Test
    void unassignClearsCurrentAssigneeAndClosesHistory() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.assign(created.id(), ASSIGNEE, null, 0L, ACTOR, false, "c");
        incidentService.unassign(created.id(), 1L, ACTOR, "c");

        assertThat(currentAssignee(created.id())).isNull();
        assertThat(activeAssignmentCount(created.id())).isZero();
        assertThat(assignmentCount(created.id())).isEqualTo(1L); // record retained, just closed
    }

    @Test
    void unknownAssigneeIsRejected() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        UUID ghost = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
        assertThatThrownBy(() -> incidentService.assign(created.id(), ghost, null, 0L, ACTOR, false, "c"))
                .isInstanceOf(UnknownReferenceException.class);
        assertThat(currentAssignee(created.id())).isNull(); // unchanged
    }

    @Test
    void engineerSelfAssignRestrictionEnforced() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        // restrictedToSelf, assignee != actor → forbidden, nothing persisted.
        assertThatThrownBy(() -> incidentService.assign(created.id(), ASSIGNEE, null, 0L, ACTOR, true, "c"))
                .isInstanceOf(ForbiddenAssignmentException.class);
        assertThat(assignmentCount(created.id())).isZero();
        // Self-assign allowed.
        incidentService.assign(created.id(), ACTOR, null, 0L, ACTOR, true, "c");
        assertThat(currentAssignee(created.id())).isEqualTo(ACTOR.toString());
    }

    @Test
    void staleAssignmentRejectedWritesNoHistoryOrAudit() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.assign(created.id(), ASSIGNEE, null, 0L, ACTOR, false, "c"); // v1
        assertThatThrownBy(() -> incidentService.assign(created.id(), ACTOR, null, 0L, ACTOR, false, "c"))
                .isInstanceOf(StaleIncidentVersionException.class);
        assertThat(assignmentCount(created.id())).isEqualTo(1L);
        assertThat(version(created.id())).isEqualTo(1L);
    }

    @Test
    void concurrentAssignSameVersionYieldsExactlyOneWinner() throws Exception {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();

        Callable<Void> task = () -> {
            ready.countDown();
            go.await();
            try {
                incidentService.assign(created.id(), ASSIGNEE, null, 0L, ACTOR, false, "c");
                ok.incrementAndGet();
            } catch (StaleIncidentVersionException e) {
                stale.incrementAndGet();
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

        assertThat(ok.get()).isEqualTo(1);
        assertThat(stale.get()).isEqualTo(1);
        assertThat(version(created.id())).isEqualTo(1L);
        assertThat(assignmentCount(created.id())).isEqualTo(1L); // only the winner recorded history
    }

    @Test
    void commentsPersistAppendOnlyWithAuditAndNoVersionBump() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        incidentService.addComment(created.id(), "first note", CommentCategory.NOTE, ACTOR, "c");
        incidentService.addComment(created.id(), "investigating", CommentCategory.INVESTIGATION, ACTOR, "c");

        List<IncidentComment> listed = incidentService.listComments(created.id());
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).body()).isEqualTo("first note");
        assertThat(version(created.id())).isZero(); // comments do not bump incident version
        Long commentAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE action='INCIDENT_COMMENTED' AND resource_id = ?::uuid",
                Long.class, created.id().toString());
        assertThat(commentAudits).isEqualTo(2L);
    }

    @Test
    void invalidCommentCategoryRejectedByDbCheck() {
        Incident created = incidentService.create(createCommand(), ACTOR, "c");
        UUID commentId = UUID.fromString("018f7000-0000-7000-8000-000000000001");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO incident_comments (id, incident_id, author_id, category, body, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'BOGUS', 'x', ?)
                """, commentId.toString(), created.id().toString(), ACTOR.toString(),
                Timestamp.from(Instant.parse("2026-03-20T00:00:00Z"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
