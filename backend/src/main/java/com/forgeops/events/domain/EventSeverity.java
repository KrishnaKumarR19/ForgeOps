package com.forgeops.events.domain;

/**
 * Severity hint supplied by (or derived for) an operational event (DOMAIN_MODEL.md §2 —
 * severity is a "hint" that informs a later incident's severity). Optional on submission.
 *
 * <p>The domain fixes a small, ordered set for v1. The documents describe severity as a hint
 * without enumerating values, so this concrete set is the implementation's choice and is
 * enforced by both the API validation and the {@code ck_operational_events_severity} CHECK
 * constraint. Kept framework-free (ADR-0030).
 */
public enum EventSeverity {
    INFO,
    WARNING,
    MINOR,
    MAJOR,
    CRITICAL
}
