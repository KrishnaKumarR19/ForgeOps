package com.forgeops.incidents.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for the {@code POST /incidents/{id}/severity} command (API_CONTRACTS.md §10). The new
 * severity value is required; an invalid value yields {@code 400}.
 *
 * @param severity required severity (INFO|WARNING|MINOR|MAJOR|CRITICAL)
 */
public record ChangeSeverityRequest(@NotBlank String severity) {
}
