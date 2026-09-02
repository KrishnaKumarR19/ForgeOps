package com.forgeops.events.domain;

/**
 * Signals that a save violated the authoritative {@code (client_id, idempotency_key)}
 * uniqueness constraint — i.e. another submission with the same client and key already
 * exists. This is how the persistence boundary reports a concurrent-duplicate race that the
 * application-level pre-check could not see. The application layer catches it, re-reads the
 * winning event, and then decides replay (same payload) versus conflict (different payload).
 *
 * <p>Framework-free: carries no JPA/Spring types so it can be part of the domain port
 * contract (ADR-0030).
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
