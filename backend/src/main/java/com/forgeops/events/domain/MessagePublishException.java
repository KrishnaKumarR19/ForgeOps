package com.forgeops.events.domain;

/**
 * Signals that an outbox message could not be confirmed as accepted by the broker (nack,
 * timeout, connection/channel failure, or broker unavailable). Framework-free so it can be
 * part of the {@link MessageBroker} port contract. The publisher treats this as a retryable
 * failure and leaves the outbox row {@code PENDING} (INV-OUTBOX-003).
 */
public class MessagePublishException extends RuntimeException {

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }

    public MessagePublishException(String message) {
        super(message);
    }
}
