package com.forgeops.incidents.application;

import java.util.UUID;

/**
 * Outcome of {@link IncidentDetectionPort#correlateOrCreate}: the incident the event belongs to
 * and whether it was newly created (vs. correlated to an existing active incident). The events
 * side uses {@code incidentId} to set {@code operational_events.incident_id}.
 *
 * @param incidentId the incident the event is associated with
 * @param created    true if a new incident was created; false if correlated to an existing one
 */
public record DetectionResult(UUID incidentId, boolean created) {
}
