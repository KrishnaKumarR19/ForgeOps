package com.forgeops.events.application;

/**
 * Raised when a submitted {@code service} or {@code environment} key does not reference known
 * reference data (API_CONTRACTS.md §6). Maps to {@code 422 Unprocessable Content} (RFC 9457)
 * at the API boundary; no event is persisted. Distinct from a {@code 400} syntactic
 * validation error — the request is well-formed but references an unknown resource.
 */
public class UnknownReferenceException extends RuntimeException {

    public UnknownReferenceException(String message) {
        super(message);
    }
}
