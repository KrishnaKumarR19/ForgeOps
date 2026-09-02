package com.forgeops.events.domain;

/**
 * Lifecycle state of an outbox message (ADR-0019, DOMAIN_MODEL.md §9). Only two real states:
 * {@code PENDING} on creation (awaiting publication) and {@code PUBLISHED} once the broker has
 * accepted it. A retryable failure is simply {@code PENDING} with a positive attempt count —
 * no separate FAILED state. This slice only ever creates {@code PENDING} messages; the
 * {@code PUBLISHED} transition belongs to the later publisher slice.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
