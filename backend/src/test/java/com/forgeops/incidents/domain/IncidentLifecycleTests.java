package com.forgeops.incidents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain unit tests for the incident lifecycle state machine (Phase 7 Slice 2, DOMAIN_MODEL §10,
 * INV-INC-002). Verifies every documented transition, representative invalid transitions,
 * severity change rules, timestamp side effects (resolved_at/closed_at, reopen preservation),
 * and version increment. Synthetic data; no persistence.
 */
class IncidentLifecycleTests {

    private static final UUID ID = UUID.fromString("018f5000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-03-20T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-20T01:00:00Z");

    private Incident open() {
        return Incident.open(ID, "t", SERVICE_ID, ENV_ID, "sig", IncidentSeverity.MAJOR, null, T0);
    }

    // ----- valid transitions ----------------------------------------------------------------

    @Test
    void openToAcknowledged() {
        Incident next = open().acknowledge(T1);
        assertThat(next.state()).isEqualTo(IncidentState.ACKNOWLEDGED);
        assertThat(next.version()).isEqualTo(1L);
    }

    @Test
    void openToInvestigating() {
        assertThat(open().investigate(T1).state()).isEqualTo(IncidentState.INVESTIGATING);
    }

    @Test
    void acknowledgedToInvestigating() {
        assertThat(open().acknowledge(T1).investigate(T1).state()).isEqualTo(IncidentState.INVESTIGATING);
    }

    @Test
    void investigatingToMitigated() {
        assertThat(open().investigate(T1).mitigate(T1).state()).isEqualTo(IncidentState.MITIGATED);
    }

    @Test
    void mitigatedToResolvedSetsResolvedAt() {
        Incident resolved = open().investigate(T1).mitigate(T1).resolve(T1);
        assertThat(resolved.state()).isEqualTo(IncidentState.RESOLVED);
        assertThat(resolved.resolvedAt()).contains(T1);
        assertThat(resolved.closedAt()).isEmpty();
        assertThat(resolved.version()).isEqualTo(3L); // investigate, mitigate, resolve
    }

    @Test
    void mitigatedToInvestigatingRegression() {
        Incident regressed = open().investigate(T1).mitigate(T1).investigate(T1);
        assertThat(regressed.state()).isEqualTo(IncidentState.INVESTIGATING);
    }

    @Test
    void resolvedToClosedSetsClosedAt() {
        Incident closed = open().investigate(T1).mitigate(T1).resolve(T1).close(T1);
        assertThat(closed.state()).isEqualTo(IncidentState.CLOSED);
        assertThat(closed.resolvedAt()).contains(T1); // preserved
        assertThat(closed.closedAt()).contains(T1);
    }

    @Test
    void resolvedToInvestigatingReopenPreservesTimestamps() {
        Incident resolved = open().investigate(T0).mitigate(T0).resolve(T0);
        Incident reopened = resolved.investigate(T1);
        assertThat(reopened.state()).isEqualTo(IncidentState.INVESTIGATING);
        // Reopen behavior is unspecified by the docs; we PRESERVE resolved_at (documented decision).
        assertThat(reopened.resolvedAt()).contains(T0);
    }

    // ----- invalid transitions (rejected, INV-INC-002) --------------------------------------

    @Test
    void openCannotResolve() {
        assertThatThrownBy(() -> open().resolve(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void openCannotClose() {
        assertThatThrownBy(() -> open().close(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void openCannotMitigate() {
        assertThatThrownBy(() -> open().mitigate(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void acknowledgedCannotAcknowledgeAgain() {
        assertThatThrownBy(() -> open().acknowledge(T1).acknowledge(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void investigatingCannotResolveDirectly() {
        assertThatThrownBy(() -> open().investigate(T1).resolve(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void closedIsTerminal() {
        Incident closed = open().investigate(T0).mitigate(T0).resolve(T0).close(T0);
        assertThatThrownBy(() -> closed.investigate(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
        assertThatThrownBy(() -> closed.acknowledge(T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    // ----- severity ------------------------------------------------------------------------

    @Test
    void severityChangeAllowedInNonTerminalStateAndIncrementsVersion() {
        Incident changed = open().changeSeverity(IncidentSeverity.CRITICAL, T1);
        assertThat(changed.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(changed.state()).isEqualTo(IncidentState.OPEN); // severity is not a lifecycle move
        assertThat(changed.version()).isEqualTo(1L);
    }

    @Test
    void severityChangeRejectedWhenClosed() {
        Incident closed = open().investigate(T0).mitigate(T0).resolve(T0).close(T0);
        assertThatThrownBy(() -> closed.changeSeverity(IncidentSeverity.MINOR, T1))
                .isInstanceOf(IllegalIncidentTransitionException.class);
    }

    @Test
    void severityCannotBeNull() {
        assertThatThrownBy(() -> open().changeSeverity(null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- version --------------------------------------------------------------------------

    @Test
    void eachTransitionIncrementsVersionByOne() {
        Incident i0 = open();
        assertThat(i0.version()).isZero();
        Incident i1 = i0.acknowledge(T1);
        assertThat(i1.version()).isEqualTo(1L);
        Incident i2 = i1.investigate(T1);
        assertThat(i2.version()).isEqualTo(2L);
    }
}
