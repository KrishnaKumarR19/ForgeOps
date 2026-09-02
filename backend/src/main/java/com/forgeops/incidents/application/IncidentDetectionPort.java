package com.forgeops.incidents.application;

import com.forgeops.incidents.domain.DetectionContext;

/**
 * Published application port for event-driven incident detection/correlation (Phase 7 Slice 4,
 * ADR-0017, ADR-0020). The events module depends on this interface (not on any incidents
 * infrastructure) to hand a processed operational event to the incidents domain. The call runs
 * inside the caller's transaction so the incident create/correlate and its audit commit
 * atomically with the event's association and status transition (PERSISTENCE_MODEL §18,
 * INV-INC-007).
 *
 * <p>No infrastructure types are exposed; input is the framework-free {@link DetectionContext}
 * and output the {@link DetectionResult}.
 */
public interface IncidentDetectionPort {

    /**
     * Correlates the event to an existing active incident, or creates a new OPEN incident if
     * none matches, and writes the SYSTEM audit entry. Deterministic and rule-based (no ML).
     *
     * @return the incident the event belongs to, and whether it was newly created
     */
    DetectionResult correlateOrCreate(DetectionContext context);
}
