package com.forgeops.incidents.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value carrying exactly the fields detection needs about an accepted operational
 * event (Phase 7 Slice 4). Passed from the events module to the incidents detection port so the
 * incidents side never reads the events module's persistence internals (module boundary,
 * ADR-0030). Framework-free.
 *
 * @param eventId          the accepted event's id (to associate on correlation/creation)
 * @param serviceId        emitting service (correlation key)
 * @param environmentId    scoping environment (correlation key)
 * @param eventType        producer event type (signature fallback)
 * @param severity         event severity hint, or null (→ default on incident creation)
 * @param failureSignature producer failure signature, or null (→ event type fallback)
 * @param serviceKey       resolved service key (for the generated title), may be null
 * @param environmentKey   resolved environment key (for the generated title), may be null
 * @param receivedAt       server-side acceptance time (authoritative correlation timestamp)
 */
public record DetectionContext(
        UUID eventId,
        UUID serviceId,
        UUID environmentId,
        String eventType,
        IncidentSeverity severity,
        String failureSignature,
        String serviceKey,
        String environmentKey,
        Instant receivedAt) {

    public DetectionContext {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (serviceId == null) {
            throw new IllegalArgumentException("serviceId is required");
        }
        if (environmentId == null) {
            throw new IllegalArgumentException("environmentId is required");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt is required");
        }
    }
}
