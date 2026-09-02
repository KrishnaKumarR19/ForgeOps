package com.forgeops.incidents.application;

import java.util.UUID;

/**
 * The requested incident does not exist. Mapped by the API layer to {@code 404 Not Found}
 * (RFC 9457).
 */
public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(UUID id) {
        super("Incident not found: " + id);
    }
}
