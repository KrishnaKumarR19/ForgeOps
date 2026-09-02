package com.forgeops.events.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal RabbitMQ topology names for the outbox publisher (Phase 6 Slice 2). These are
 * <strong>internal</strong> implementation details, not a public API contract — the docs do
 * not fix topology names (approved Category-B choice). Configuration-driven with safe
 * deterministic defaults; overridable per environment.
 *
 * <p>The dead-letter names support Phase 6 Slice 3 (INV-MSG-006, FR-RL-5): the processing
 * queue is declared with {@code x-dead-letter-exchange} pointing at {@code deadLetterExchange},
 * which routes to {@code deadLetterQueue} where a message goes after the consumer's bounded
 * retries are exhausted. Also internal, not a public contract.
 *
 * @param exchange           durable topic exchange the events are published to
 * @param queue              durable queue bound to the exchange (the consumer reads this)
 * @param routingKey         routing key used for accepted operational events
 * @param deadLetterExchange durable exchange failed messages are dead-lettered to
 * @param deadLetterQueue    durable queue bound to the dead-letter exchange
 * @param deadLetterRoutingKey routing key used when dead-lettering
 */
@ConfigurationProperties(prefix = "forgeops.events.rabbitmq")
public record RabbitMqTopologyProperties(
        String exchange,
        String queue,
        String routingKey,
        String deadLetterExchange,
        String deadLetterQueue,
        String deadLetterRoutingKey) {

    public RabbitMqTopologyProperties {
        exchange = (exchange == null || exchange.isBlank()) ? "forgeops.events" : exchange;
        queue = (queue == null || queue.isBlank()) ? "forgeops.events.processing" : queue;
        routingKey = (routingKey == null || routingKey.isBlank())
                ? "operational-event.received" : routingKey;
        deadLetterExchange = (deadLetterExchange == null || deadLetterExchange.isBlank())
                ? "forgeops.events.dlx" : deadLetterExchange;
        deadLetterQueue = (deadLetterQueue == null || deadLetterQueue.isBlank())
                ? "forgeops.events.processing.dlq" : deadLetterQueue;
        deadLetterRoutingKey = (deadLetterRoutingKey == null || deadLetterRoutingKey.isBlank())
                ? "operational-event.dead-letter" : deadLetterRoutingKey;
    }
}
