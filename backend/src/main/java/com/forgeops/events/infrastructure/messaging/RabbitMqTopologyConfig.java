package com.forgeops.events.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the outbox publisher's RabbitMQ topology (Phase 6 Slice 2, ADR-0013): a durable
 * topic exchange, a durable non-auto-delete queue, and a binding on the configured routing
 * key. Spring AMQP's {@code RabbitAdmin} declares these on the broker at startup. Durability
 * ensures committed work is not lost across a broker restart (INV-OUTBOX-002). No dead-letter
 * exchange/queue is declared here — that is a later slice (INV-MSG-006).
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
        return QueueBuilder.durable(props.queue()).build();
    }

    @Bean
    Binding forgeopsEventsBinding(Queue forgeopsEventsProcessingQueue,
                                  TopicExchange forgeopsEventsExchange,
                                  RabbitMqTopologyProperties props) {
        return BindingBuilder.bind(forgeopsEventsProcessingQueue)
                .to(forgeopsEventsExchange)
                .with(props.routingKey());
    }
}
