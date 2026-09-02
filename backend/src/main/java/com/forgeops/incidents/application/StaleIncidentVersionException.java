package com.forgeops.incidents.application;

/**
 * The client's {@code If-Match} version no longer matches the stored incident version — a
 * concurrent update won the race (INV-INC-005, ADR-0028). Mapped by the API layer to
 * {@code 412 Precondition Failed}; the write is rejected with no silent overwrite.
 */
public class StaleIncidentVersionException extends RuntimeException {

    public StaleIncidentVersionException(String message) {
        super(message);
    }
}
