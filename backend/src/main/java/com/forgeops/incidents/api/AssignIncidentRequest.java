package com.forgeops.incidents.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Assignment request body (API_CONTRACTS.md §12): {@code assignee_id} (required) plus an
 * optional {@code team}. Snake_case matches the contract. The acting user ({@code assigned_by})
 * is the JWT principal, never the body (INV-SEC-005). An invalid UUID yields {@code 400}; an
 * unknown assignee yields {@code 422}.
 *
 * @param assigneeId required assignee user id (UUID)
 * @param team       optional team ownership (bounded length)
 */
public record AssignIncidentRequest(
        @JsonProperty("assignee_id") @NotBlank String assigneeId,
        @Size(max = 200) String team) {
}
