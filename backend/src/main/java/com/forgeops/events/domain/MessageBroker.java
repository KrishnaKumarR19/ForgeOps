package com.forgeops.events.domain;

/**
 * Application/domain-facing port for handing an outbox message to the asynchronous transport
 * (ADR-0013, ADR-0030). The infrastructure adapter publishes to RabbitMQ; the domain and
 * application layers depend only on this framework-free abstraction — no RabbitMQ/Spring types
 * leak inward.
 *
 * <p>A publish is only successful when the broker has <em>confirmed</em> acceptance
 * (ADR-0019). Any failure — nack, timeout, connection/channel failure, broker unavailable — is
 * signalled by throwing {@link MessagePublishException}; the caller then leaves the outbox row
 * retryable (INV-OUTBOX-003). Delivery is at-least-once (INV-MSG-001): a crash after broker
 * acceptance but before the caller commits the {@code PUBLISHED} mark may cause a later
 * duplicate publication, which is expected and tolerated by (future) idempotent consumers.
 */
public interface MessageBroker {

    /**
     * Publishes the message and blocks until the broker confirms acceptance.
     *
     * @throws MessagePublishException if the broker does not confirm acceptance
     */
    void publish(OutboxMessage message);
}
