package com.forgeops.events.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.events.application.EventConsumerProperties;
import com.forgeops.events.application.EventProcessingService;
import com.forgeops.events.application.NonRetryableEventProcessingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ consumer for accepted operational events (Phase 6 Slice 3, FR-EV-5 consumer side,
 * ADR-0014). Listens on the durable processing queue and hands each message to the idempotent
 * {@link EventProcessingService}. The container factory
 * ({@link EventConsumerConfig#LISTENER_CONTAINER_FACTORY}) supplies the acknowledgement,
 * bounded-retry, and dead-letter behavior; this class contains only message decoding and the
 * call into the application use case.
 *
 * <p>Delivery is at-least-once (INV-MSG-001): the same message may be delivered more than once
 * (duplicate publication, redelivery after a crash-before-ack). Correctness comes from the
 * idempotent effect in the use case, not from assuming exactly-once delivery.
 *
 * <p>Acknowledgement timing (INV-MSG-004): the container acknowledges only after this method
 * returns normally, which happens only after the use case has committed the {@code
 * RECEIVED → PROCESSED} transition. Any exception thrown here causes a reject, driving the
 * retry/dead-letter machinery. A message whose body cannot be parsed or lacks a valid
 * {@code event_id} is a poison message: it is reported as
 * {@link NonRetryableEventProcessingException} so it is dead-lettered immediately rather than
 * retried (INV-MSG-006).
 *
 * <p>Enabled by default; {@code forgeops.events.consumer.enabled=false} removes the listener so
 * tests can drive delivery deterministically without a live consumer racing their assertions
 * (mirrors the outbox publisher's enable flag).
 *
 * <p>Logging carries identifiers only (outbox message id, event id, message type) — never the
 * payload body, credentials, or secrets.
 */
@Component
@ConditionalOnProperty(name = "forgeops.events.consumer.enabled", havingValue = "true",
        matchIfMissing = true)
class OperationalEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OperationalEventConsumer.class);

    private final EventProcessingService processingService;
    private final ObjectMapper objectMapper;

    OperationalEventConsumer(EventProcessingService processingService, ObjectMapper objectMapper) {
        this.processingService = processingService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(
            queues = "${forgeops.events.rabbitmq.queue:forgeops.events.processing}",
            containerFactory = EventConsumerConfig.LISTENER_CONTAINER_FACTORY)
    void onMessage(Message message) {
        // Stable identifiers for correlation; never log the payload body.
        String outboxMessageId = message.getMessageProperties().getMessageId();
        String messageType = message.getMessageProperties().getType();
        UUID eventId = extractEventId(message, outboxMessageId);

        log.debug("Consuming event message: outboxMessageId={} messageType={} eventId={}",
                outboxMessageId, messageType, eventId);

        // Let the outcome be handled/logged by the use case. Any exception propagates so the
        // container rejects the message (AUTO ack) and the retry/DLQ machinery takes over.
        processingService.process(eventId);
    }

    /**
     * Extracts the authoritative {@code event_id} from the canonical JSON body (the Slice 2
     * publisher writes it there). A missing/unparseable body or id is a poison message and is
     * reported as non-retryable so it is dead-lettered rather than retried forever.
     */
    private UUID extractEventId(Message message, String outboxMessageId) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new NonRetryableEventProcessingException(
                    "Unparseable event message body (outboxMessageId=" + outboxMessageId + ")", e);
        }
        JsonNode eventIdNode = root.get("event_id");
        if (eventIdNode == null || eventIdNode.isNull() || !eventIdNode.isTextual()) {
            throw new NonRetryableEventProcessingException(
                    "Event message missing event_id (outboxMessageId=" + outboxMessageId + ")");
        }
        try {
            return UUID.fromString(eventIdNode.asText());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventProcessingException(
                    "Event message has non-UUID event_id (outboxMessageId=" + outboxMessageId + ")", e);
        }
    }
}
