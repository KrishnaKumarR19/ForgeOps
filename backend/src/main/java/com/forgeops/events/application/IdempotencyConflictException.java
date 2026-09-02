package com.forgeops.events.application;

/**
 * Raised when an idempotency key is reused by the same authenticated client with a different
 * payload (API_CONTRACTS.md §7 Case C, ADR-0025). The key is already bound to a different
 * submission, so the request conflicts with existing state and is rejected with {@code 409};
 * the original event is never mutated. The message is generic and reveals no stored content.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
