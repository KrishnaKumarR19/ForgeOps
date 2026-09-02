package com.forgeops.events.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Accepted-event representation (API_CONTRACTS.md §6/§26): the event's server-assigned
 * {@code id}, its attributes, {@code received_at}, {@code status}, nullable
 * {@code incident_id}, and {@code payload}. Deliberately excludes {@code payload_hash} and
 * any outbox reference (§26). Field names are the contract's snake_case.
 *
 * @param id          server-generated event id
 * @param service     service key
 * @param environment environment key
 * @param eventType   producer event type
 * @param severity    severity hint (nullable)
 * @param occurredAt  when the event happened
 * @param receivedAt  when ForgeOps accepted it
 * @param status      RECEIVED | PROCESSED
 * @param incidentId  owning incident id, or null if uncorrelated
 * @param payload     the event payload as a JSON object
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AcceptedEventResponse(
        String id,
        String service,
        String environment,
        @JsonProperty("event_type") String eventType,
        String severity,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("received_at") Instant receivedAt,
        String status,
        @JsonProperty("incident_id") String incidentId,
        JsonNode payload) {
}
