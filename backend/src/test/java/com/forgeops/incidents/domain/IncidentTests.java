package com.forgeops.incidents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Incident} aggregate foundation (Phase 7 Slice 1): construction and
 * the foundation invariants (required id/service/environment/severity/state, non-negative
 * version, optional assignee/resolved_at/closed_at). Lifecycle transitions are NOT part of this
 * slice and are not tested here. Synthetic data.
 */
class IncidentTests {

    private static final UUID ID = UUID.fromString("018f5000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");
    private static final UUID ASSIGNEE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final Instant CREATED_AT = Instant.parse("2026-03-20T00:00:00Z");

    @Test
    void opensWithSensibleDefaults() {
        Incident incident = Incident.open(ID, "Checkout 5xx spike", SERVICE_ID, ENV_ID,
                "checkout|http_5xx", IncidentSeverity.MAJOR, null, CREATED_AT);

        assertThat(incident.id()).isEqualTo(ID);
        assertThat(incident.state()).isEqualTo(IncidentState.OPEN); // new incidents start OPEN
        assertThat(incident.severity()).isEqualTo(IncidentSeverity.MAJOR);
        assertThat(incident.version()).isZero(); // version starts at 0
        assertThat(incident.title()).contains("Checkout 5xx spike");
        assertThat(incident.failureSignature()).contains("checkout|http_5xx");
        assertThat(incident.currentAssigneeId()).isEmpty(); // unassigned
        assertThat(incident.resolvedAt()).isEmpty();
        assertThat(incident.closedAt()).isEmpty();
        assertThat(incident.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void supportsAnAssignedIncident() {
        Incident incident = Incident.open(ID, null, SERVICE_ID, ENV_ID, null,
                IncidentSeverity.CRITICAL, ASSIGNEE_ID, CREATED_AT);

        assertThat(incident.currentAssigneeId()).contains(ASSIGNEE_ID);
        assertThat(incident.title()).isEmpty();          // title optional
        assertThat(incident.failureSignature()).isEmpty(); // signature optional
    }

    @Test
    void fullConstructorRehydratesAllFields() {
        Instant resolvedAt = CREATED_AT.plusSeconds(3600);
        Instant closedAt = CREATED_AT.plusSeconds(7200);
        Incident incident = new Incident(ID, "t", SERVICE_ID, ENV_ID, "sig",
                IncidentSeverity.MINOR, IncidentState.CLOSED, ASSIGNEE_ID, 5L,
                CREATED_AT, resolvedAt, closedAt);

        assertThat(incident.state()).isEqualTo(IncidentState.CLOSED);
        assertThat(incident.version()).isEqualTo(5L);
        assertThat(incident.resolvedAt()).contains(resolvedAt);
        assertThat(incident.closedAt()).contains(closedAt);
    }

    @Test
    void requiresId() {
        assertThatThrownBy(() -> Incident.open(null, "t", SERVICE_ID, ENV_ID, "sig",
                IncidentSeverity.MAJOR, null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void requiresServiceAndEnvironment() {
        assertThatThrownBy(() -> Incident.open(ID, "t", null, ENV_ID, "sig",
                IncidentSeverity.MAJOR, null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
        assertThatThrownBy(() -> Incident.open(ID, "t", SERVICE_ID, null, "sig",
                IncidentSeverity.MAJOR, null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environmentId");
    }

    @Test
    void requiresSeverity() {
        // INV-INC-004: an incident always has a severity.
        assertThatThrownBy(() -> Incident.open(ID, "t", SERVICE_ID, ENV_ID, "sig",
                null, null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void requiresState() {
        assertThatThrownBy(() -> new Incident(ID, "t", SERVICE_ID, ENV_ID, "sig",
                IncidentSeverity.MAJOR, null, null, 0L, CREATED_AT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    void requiresCreatedAt() {
        assertThatThrownBy(() -> Incident.open(ID, "t", SERVICE_ID, ENV_ID, "sig",
                IncidentSeverity.MAJOR, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdAt");
    }

    @Test
    void rejectsNegativeVersion() {
        assertThatThrownBy(() -> new Incident(ID, "t", SERVICE_ID, ENV_ID, "sig",
                IncidentSeverity.MAJOR, IncidentState.OPEN, null, -1L, CREATED_AT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}
