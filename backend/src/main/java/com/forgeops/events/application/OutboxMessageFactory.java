package com.forgeops.events.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Builds the outbox message that is committed alongside a newly accepted operational event
 * (ADR-0013 step 2, PERSISTENCE_MODEL.md §13). The message is deterministic: its
 * {@code aggregate_id} is the event id, {@code aggregate_type} is {@code OPERATIONAL_EVENT},
 * {@code message_type} is {@code OPERATIONAL_EVENT_RECEIVED}, and {@code created_at} equals
 * the event's {@code received_at}. The message id is a server-generated UUID v7.
 *
 * <p>The payload is an internal handoff body (not a public API contract, not exposed via
 * REST): it carries the minimum a future consumer needs to identify and process the accepted
 * event — the event id plus its correlation context (service/environment/event_type/severity)
 * and timestamps. {@code message_type} constants and this payload shape are deliberately
 * internal (the authoritative docs fix the outbox columns but leave these as implementation
 * detail — ADR-0019).
 */
@Component
public class OutboxMessageFactory {

    /** Resource kind recorded on the outbox row (PERSISTENCE_MODEL.md §13). */
    public static final String AGGREGATE_TYPE = "OPERATIONAL_EVENT";
    /** Message type for a newly accepted operational event awaiting async processing. */
    public static final String MESSAGE_TYPE = "OPERATIONAL_EVENT_RECEIVED";

    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public OutboxMessageFactory(IdGenerator idGenerator, ObjectMapper objectMapper) {
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates the {@code PENDING} outbox message for an accepted event. Not persisted here —
     * the caller writes it inside the event-acceptance transaction.
     */
    public OutboxMessage forAcceptedEvent(OperationalEvent event) {
        return OutboxMessage.pending(
                idGenerator.newId(),
                MESSAGE_TYPE,
                AGGREGATE_TYPE,
                event.id(),
                buildPayload(event),
                event.receivedAt());
    }

    private String buildPayload(OperationalEvent event) {
        // Deterministic field set/order; identifies the event and its correlation context.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("event_id", event.id().toString());
        node.put("service", event.service());
        node.put("environment", event.environment());
        node.put("event_type", event.eventType());
        node.put("severity", event.severity().map(Enum::name).orElse(null));
        node.put("occurred_at", event.occurredAt().toString());
        node.put("received_at", event.receivedAt().toString());
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // ObjectNode is always serializable; treat as a programming error if not.
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
