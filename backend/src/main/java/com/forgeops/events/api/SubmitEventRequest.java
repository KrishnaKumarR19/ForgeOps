package com.forgeops.events.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Event ingestion request body (API_CONTRACTS.md §6). Field names are the contract's
 * snake_case. The authenticated producer identity is NOT part of this body — it is taken
 * from the security principal server-side (SECURITY_DESIGN.md §9). Any {@code user_id},
 * {@code client_id}, or {@code role} sent by a client is ignored.
 *
 * <p>Syntactic validation (required fields, types, timestamp/enum format) yields {@code 400};
 * payload well-formedness/size and the severity enum are validated in the service/DTO layer.
 *
 * @param service         required service key
 * @param environment     required environment key
 * @param eventType       required producer event type
 * @param occurredAt      required RFC 3339 timestamp of when the event happened
 * @param payload         required JSON object payload
 * @param severity        optional severity hint (INFO|WARNING|MINOR|MAJOR|CRITICAL)
 * @param producerEventId optional source-system id
 * @param failureSignature optional producer-computed failure signature
 */
public record SubmitEventRequest(
        @NotBlank String service,
        @NotBlank String environment,
        @JsonProperty("event_type") @NotBlank String eventType,
        @JsonProperty("occurred_at") @NotNull Instant occurredAt,
        @NotNull JsonNode payload,
        String severity,
        @JsonProperty("producer_event_id") String producerEventId,
        @JsonProperty("failure_signature") String failureSignature) {
}
