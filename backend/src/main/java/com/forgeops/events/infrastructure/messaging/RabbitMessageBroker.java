package com.forgeops.events.infrastructure.messaging;

import com.forgeops.events.domain.MessageBroker;
import com.forgeops.events.domain.MessagePublishException;
import com.forgeops.events.domain.OutboxMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ adapter implementing the {@link MessageBroker} port (ADR-0013, ADR-0030). Publishes
 * an outbox message to the configured topic exchange and blocks until the broker
 * <strong>confirms</strong> acceptance, so a publish is only reported successful when the
 * broker actually accepted it (ADR-0019). A nack, confirm timeout, or connection/channel
 * failure is surfaced as {@link MessagePublishException} so the publisher leaves the row
 * retryable (INV-OUTBOX-003).
 *
 * <p>Message mapping (approved reconnaissance §9): the stored canonical JSON {@code payload} is
 * published verbatim with {@code content_type=application/json}; delivery is persistent; the
 * AMQP {@code messageId} is the outbox row id (stable across retries/duplicates); headers carry
 * {@code aggregate_id}, {@code message_type}, and (when present) {@code correlation_id} so the
 * future consumer has a stable dedup/identity key. No second business identity is generated.
 */
@Component
class RabbitMessageBroker implements MessageBroker {

    static final String HEADER_AGGREGATE_ID = "aggregate_id";
    static final String HEADER_MESSAGE_TYPE = "message_type";
    static final String HEADER_CORRELATION_ID = "correlation_id";

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqTopologyProperties topology;
    private final long confirmTimeoutMs;

    RabbitMessageBroker(RabbitTemplate rabbitTemplate, RabbitMqTopologyProperties topology) {
        this.rabbitTemplate = rabbitTemplate;
        this.topology = topology;
        this.confirmTimeoutMs = 5_000L;
    }

    @Override
    public void publish(OutboxMessage message) {
        Message amqpMessage = toAmqpMessage(message);
        try {
            // invoke(...) scopes the operations to a single channel so waitForConfirms applies
            // to this publish. A false return = no positive confirm (nack/timeout) = failure.
            Boolean confirmed = rabbitTemplate.invoke(operations -> {
                operations.send(topology.exchange(), topology.routingKey(), amqpMessage);
                return operations.waitForConfirms(confirmTimeoutMs);
            });
            if (!Boolean.TRUE.equals(confirmed)) {
                throw new MessagePublishException(
                        "Broker did not confirm acceptance for outbox message " + message.id());
            }
        } catch (MessagePublishException e) {
            throw e;
        } catch (AmqpException e) {
            // Connection/channel failure, broker unavailable, serialization, etc.
            throw new MessagePublishException(
                    "Failed to publish outbox message " + message.id(), e);
        } catch (RuntimeException e) {
            // Any other publish-path failure (e.g. confirm/channel state) is treated as a
            // retryable publish failure rather than escaping as an unhandled error.
            throw new MessagePublishException(
                    "Failed to publish outbox message " + message.id(), e);
        }
    }

    private Message toAmqpMessage(OutboxMessage message) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(message.id().toString());
        props.setType(message.messageType());
        props.setHeader(HEADER_AGGREGATE_ID, message.aggregateId().toString());
        props.setHeader(HEADER_MESSAGE_TYPE, message.messageType());
        return new Message(message.payload().getBytes(StandardCharsets.UTF_8), props);
    }
}
