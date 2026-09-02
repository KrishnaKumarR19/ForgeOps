package com.forgeops.incidents.application;

import com.forgeops.incidents.domain.IncidentSeverity;

/**
 * Application input for manually creating an incident (Phase 7 Slice 2, FR-IN-1). Carries only
 * the fields a user may set at creation; the state (always {@code OPEN}), id, version, and
 * timestamps are server-established. The authenticated actor is passed separately and is never
 * part of this command (INV-SEC-005).
 *
 * @param service          service key (resolved/validated against reference data)
 * @param environment      environment key (resolved/validated against reference data)
 * @param severity         required severity (INV-INC-004)
 * @param title            optional human-readable summary
 * @param failureSignature optional correlation signature
 */
public record CreateIncidentCommand(
        String service,
        String environment,
        IncidentSeverity severity,
        String title,
        String failureSignature) {
}
