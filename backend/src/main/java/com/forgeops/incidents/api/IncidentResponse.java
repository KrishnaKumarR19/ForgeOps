package com.forgeops.incidents.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Incident representation returned by the API (API_CONTRACTS.md §26). The {@code version} is
 * deliberately NOT a body field — it is surfaced only as the {@code ETag} header (ADR-0028).
 * {@code service}/{@code environment} are the resolved reference keys; {@code current_assignee}
 * is null in this slice (assignment is Slice 3). Snake_case matches the contract.
 *
 * @param id             incident id
 * @param title          optional summary
 * @param service        service key
 * @param environment    environment key
 * @param severity       current severity
 * @param state          current lifecycle state
 * @param currentAssignee current assignee id (null in this slice)
 * @param createdAt      creation time
 * @param resolvedAt     set when RESOLVED (else null)
 * @param closedAt       set when CLOSED (else null)
 */
public record IncidentResponse(
        String id,
        String title,
        String service,
        String environment,
        String severity,
        String state,
        @JsonProperty("current_assignee") String currentAssignee,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("resolved_at") Instant resolvedAt,
        @JsonProperty("closed_at") Instant closedAt) {
}
