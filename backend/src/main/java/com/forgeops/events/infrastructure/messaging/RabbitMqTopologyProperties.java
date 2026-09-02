package com.forgeops.events.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal RabbitMQ topology names for the outbox publisher (Phase 6 Slice 2). These are
 * <strong>internal</strong> implementation details, not a public API contract — the docs do
 * not fix topology names (approved Category-B choice). Configuration-driven with safe
 * deterministic defaults; overridable per environment.
 *
 * @param exchange   durable topic exchange the events are published to
 * @param queue      durable queue bound to the exchange (the future consumer reads this)
 * @param routingKey routing key used for accepted operational events
 */
@ConfigurationProperties(prefix = "forgeops.events.rabbitmq")
public record RabbitMqTopologyProperties(
        String exchange,
        String queue,
        String routingKey) {

    public RabbitMqTopologyProperties {
        exchange = (exchange == null || exchange.isBlank()) ? "forgeops.events" : exchange;
        queue = (queue == null || queue.isBlank()) ? "forgeops.events.processing" : queue;
        routingKey = (routingKey == null || routingKey.isBlank())
                ? "operational-event.received" : routingKey;
    }
}
