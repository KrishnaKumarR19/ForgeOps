package com.forgeops.incidents.domain;

import java.util.UUID;

/**
 * Read-only check that a user id refers to a real persisted user, used to validate an
 * assignment target before it is applied (so an unknown assignee yields a clean domain-level
 * rejection rather than a raw FK violation). The incidents module owns its own port (ADR-0030);
 * the infrastructure adapter reads the shared {@code users} table. Framework-free.
 */
public interface UserExistenceReader {

    /** Returns true if a user row with this id exists. */
    boolean exists(UUID userId);
}
