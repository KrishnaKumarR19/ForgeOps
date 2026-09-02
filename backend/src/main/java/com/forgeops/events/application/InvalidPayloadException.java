package com.forgeops.events.application;

/**
 * Raised when an event payload is missing or not a well-formed JSON object. Maps to a
 * {@code 400} validation error (RFC 9457) at the API boundary; no event is persisted.
 */
public class InvalidPayloadException extends RuntimeException {

    public InvalidPayloadException(String message) {
        super(message);
    }
}
