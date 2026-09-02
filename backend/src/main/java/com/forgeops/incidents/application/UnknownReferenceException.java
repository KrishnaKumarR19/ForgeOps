package com.forgeops.incidents.application;

/**
 * A manual incident referenced an unknown service or environment key. Mapped by the API layer
 * to {@code 422 Unprocessable Content} (API_CONTRACTS.md §18/§19).
 */
public class UnknownReferenceException extends RuntimeException {

    public UnknownReferenceException(String message) {
        super(message);
    }
}
