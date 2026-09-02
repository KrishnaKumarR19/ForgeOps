package com.forgeops.incidents.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Comment creation body (API_CONTRACTS.md §13): required {@code body}, optional {@code category}
 * (NOTE / INVESTIGATION / RESOLUTION). An invalid category value yields {@code 400}.
 *
 * @param body     required comment content (bounded length)
 * @param category optional category
 */
public record AddCommentRequest(
        @NotBlank @Size(max = 10000) String body,
        String category) {
}
