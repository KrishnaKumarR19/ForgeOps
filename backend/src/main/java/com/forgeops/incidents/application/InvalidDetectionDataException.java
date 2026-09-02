package com.forgeops.incidents.application;

/**
 * The event carries detection data that can never yield a valid correlation signature (e.g. a
 * blank failure signature and blank event type). This is a poison condition — retrying cannot
 * help — so the events consumer maps it to its non-retryable / dead-letter path (INV-MSG-006).
 */
public class InvalidDetectionDataException extends RuntimeException {

    public InvalidDetectionDataException(String message) {
        super(message);
    }
}
