package com.forgeops.incidents.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Manual incident creation request body (API_CONTRACTS.md §9, FR-IN-1). Field names are the
 * contract's snake_case. The incident always starts {@code OPEN} — state is server-set and is
 * NOT a client field (ADR-0027). The actor is taken from the JWT principal, never from the body
 * (INV-SEC-005).
 *
 * <p>{@code service}, {@code environment}, and {@code severity} are required; {@code title} and
 * {@code failure_signature} are optional. Syntactic validation failures yield {@code 400}; an
 * unknown service/environment key yields {@code 422}; an invalid severity value yields
 * {@code 400}.
 *
 * @param service          required service key
 * @param environment      required environment key
 * @param severity         required severity (INFO|WARNING|MINOR|MAJOR|CRITICAL)
 * @param title            optional summary (bounded length)
 * @param failureSignature optional correlation signature (bounded length)
 */
public record CreateIncidentRequest(
        @NotBlank String service,
        @NotBlank String environment,
        @NotBlank String severity,
        @Size(max = 500) String title,
        @com.fasterxml.jackson.annotation.JsonProperty("failure_signature")
        @Size(max = 1000) String failureSignature) {
}
