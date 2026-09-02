package com.forgeops.events.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.forgeops.events.domain.EventSeverity;
import java.time.Instant;
import java.util.UUID;

/**
 * Application input for ingesting one operational event. The {@code clientId} is the
 * authenticated principal's id, set by the API layer from the security principal — never from
 * the request body/header (SECURITY_DESIGN.md §9, INV-SEC-005). The {@code idempotencyKey}
 * comes from the {@code Idempotency-Key} header. {@code payload} is the parsed JSON tree; the
 * service canonicalizes and hashes it.
 *
 * @param clientId        authenticated submitting principal (idempotency scope)
 * @param idempotencyKey  optional request-idempotency token (from the Idempotency-Key header)
 * @param service         service key
 * @param environment     environment key
 * @param eventType       producer-supplied event type
 * @param severity        optional severity hint
 * @param producerEventId optional source-system id
 * @param failureSignature optional producer-computed failure signature
 * @param occurredAt      when the event happened (producer clock)
 * @param payload         the event payload as a parsed JSON object
 */
public record IngestEventCommand(
        UUID clientId,
        String idempotencyKey,
        String service,
        String environment,
        String eventType,
        EventSeverity severity,
        String producerEventId,
        String failureSignature,
        Instant occurredAt,
        JsonNode payload) {
}
