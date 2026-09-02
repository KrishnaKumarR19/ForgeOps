package com.forgeops.incidents.domain;

/**
 * Severity of an incident (DOMAIN_MODEL.md §2/§6, INV-INC-004 — an incident always has a
 * severity). The authoritative documents describe severity as a small ordered set without
 * enumerating values, so this concrete set mirrors the operational-event severity hint
 * ({@code INFO, WARNING, MINOR, MAJOR, CRITICAL}) and is enforced by the
 * {@code ck_incidents_severity} CHECK constraint.
 *
 * <p>Defined independently in the {@code incidents} module (rather than reused from
 * {@code events}) so the incidents domain does not depend on the events module
 * (ModuleBoundaryTests). Framework-free (ADR-0030).
 */
public enum IncidentSeverity {
    INFO,
    WARNING,
    MINOR,
    MAJOR,
    CRITICAL
}
