package com.forgeops.incidents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain unit tests for incident assignment behavior (Phase 7 Slice 3): {@code assignTo}/{@code
 * unassign} set/clear the current assignee, increment version, and are rejected once CLOSED.
 * Synthetic data; no persistence.
 */
class IncidentAssignmentBehaviorTests {

    private static final UUID ID = UUID.fromString("018f5000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");
    private static final UUID ASSIGNEE = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");
    private static final Instant T0 = Instant.parse("2026-03-20T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-20T01:00:00Z");

    private Incident open() {
        return Incident.open(ID, "t", SERVICE_ID, ENV_ID, "sig", IncidentSeverity.MAJOR, null, T0);
    }

    @Test
    void assignSetsCurrentAssigneeAndBumpsVersion() {
        Incident assigned = open().assignTo(ASSIGNEE, T1);
        assertThat(assigned.currentAssigneeId()).contains(ASSIGNEE);
        assertThat(assigned.version()).isEqualTo(1L);
        assertThat(assigned.state()).isEqualTo(IncidentState.OPEN); // assignment is not a lifecycle move
    }

    @Test
    void unassignClearsCurrentAssigneeAndBumpsVersion() {
        Incident assigned = open().assignTo(ASSIGNEE, T1);
        Incident unassigned = assigned.unassign(T1);
        assertThat(unassigned.currentAssigneeId()).isEmpty();
        assertThat(unassigned.version()).isEqualTo(2L);
    }

    @Test
    void assignRequiresAssignee() {
        assertThatThrownBy(() -> open().assignTo(null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignRejectedWhenClosed() {
        Incident closed = open().investigate(T0).mitigate(T0).resolve(T0).close(T0);
        assertThatThrownBy(() -> closed.assignTo(ASSIGNEE, T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void unassignRejectedWhenClosed() {
        Incident closed = open().investigate(T0).mitigate(T0).resolve(T0).close(T0);
        assertThatThrownBy(() -> closed.unassign(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }
}
