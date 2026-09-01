package com.forgeops.common.id;

import java.util.UUID;

/**
 * Abstraction for generating primary identifiers for domain entities.
 *
 * <p>Isolated behind this small interface (rather than calling a UUID factory directly
 * throughout the code) for two concrete reasons: it lets tests inject deterministic ids,
 * and it keeps the UUID-version decision (ADR-0023: time-ordered UUID v7) in one place so
 * every entity honors it consistently. This is a minimal, justified abstraction — not
 * abstraction for its own sake.
 */
public interface IdGenerator {

    /** Returns a new time-ordered (UUID v7) identifier per ADR-0023. */
    UUID newId();
}
