package com.forgeops.events.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the events RabbitMQ topology (ADR-0013, ADR-0014). Spring AMQP's {@code RabbitAdmin}
 * declares these on the broker at startup; durability ensures committed work survives a broker
 * restart (INV-OUTBOX-002).
 *
 * <p>Publisher path (Phase 6 Slice 2): a durable topic exchange, a durable non-auto-delete
 * processing queue, and a binding on the configured routing key.
 *
 * <p>Dead-letter path (Phase 6 Slice 3, INV-MSG-006, FR-RL-5): a durable dead-letter exchange
 * and queue. The processing queue is declared with {@code x-dead-letter-exchange} /
 * {@code x-dead-letter-routing-key} arguments so that a message rejected without requeue —
 * which the consumer does once its bounded retries are exhausted — is routed by the broker to
 * the dead-letter exchange and lands on the dead-letter queue. A repeatedly failing message is
 * therefore never lost and never reprocessed forever.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RabbitMqTopologyProperties.class)
class RabbitMqTopologyConfig {

    @Bean
    TopicExchange forgeopsEventsExchange(RabbitMqTopologyProperties props) {
        // durable = true, autoDelete = false
        return new TopicExchange(props.exchange(), true, false);
    }

    @Bean
    Queue forgeopsEventsProcessingQueue(RabbitMqTopologyProperties props) {
        // Route rejected-without-requeue messages to the dead-letter exchange (INV-MSG-006).
        return QueueBuilder.durable(props.queue())
                .deadLetterExchange(props.deadLetterExchange())
                .deadLetterRoutingKey(props.deadLetterRoutingKey())
                .build();
    }

    @Bean
    Binding forgeopsEventsBinding(Queue forgeopsEventsProcessingQueue,
                                  TopicExchange forgeopsEventsExchange,
                                  RabbitMqTopologyProperties props) {
        return BindingBuilder.bind(forgeopsEventsProcessingQueue)
                .to(forgeopsEventsExchange)
                .with(props.routingKey());
    }

    // --- Dead-letter topology (Phase 6 Slice 3) ---------------------------------------------

    @Bean
    DirectExchange forgeopsEventsDeadLetterExchange(RabbitMqTopologyProperties props) {
        // A direct exchange: a single fixed dead-letter routing key maps to the DLQ.
        return new DirectExchange(props.deadLetterExchange(), true, false);
    }

    @Bean
    Queue forgeopsEventsDeadLetterQueue(RabbitMqTopologyProperties props) {
        return QueueBuilder.durable(props.deadLetterQueue()).build();
    }

    @Bean
    Binding forgeopsEventsDeadLetterBinding(Queue forgeopsEventsDeadLetterQueue,
                                            DirectExchange forgeopsEventsDeadLetterExchange,
                                            RabbitMqTopologyProperties props) {
        return BindingBuilder.bind(forgeopsEventsDeadLetterQueue)
                .to(forgeopsEventsDeadLetterExchange)
                .with(props.deadLetterRoutingKey());
    }
}
