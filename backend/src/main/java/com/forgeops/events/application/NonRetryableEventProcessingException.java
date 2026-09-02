package com.forgeops.events.application;

/**
 * Signals that an event-processing message can never succeed on retry and must be
 * dead-lettered immediately rather than retried (INV-MSG-006, FR-RL-5). Distinct from a
 * transient failure (which is retried, FR-RL-4).
 *
 * <p>Raised for poison messages — a body that cannot be parsed, a missing/invalid
 * {@code event_id}, or a {@code NOT_FOUND} event that no amount of redelivery will make
 * appear. The consumer's error handling routes these to the dead-letter path without
 * exhausting the retry budget, so a poison message neither loops forever nor is silently
 * dropped.
 */
public class NonRetryableEventProcessingException extends RuntimeException {

    public NonRetryableEventProcessingException(String message) {
        super(message);
    }

    public NonRetryableEventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
