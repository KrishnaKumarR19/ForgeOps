package com.forgeops.incidents.domain;

import java.util.List;
import java.util.UUID;

/**
 * Domain port for append-only incident comments (ADR-0030, PERSISTENCE_MODEL.md §11,
 * INV-INC-008). PostgreSQL is authoritative. Only insertion and read are exposed — comments are
 * never edited or deleted from the domain path. Framework-free.
 */
public interface IncidentCommentRepository {

    /** Appends a new comment. */
    IncidentComment save(IncidentComment comment);

    /** Comments for an incident, oldest first (read-only). */
    List<IncidentComment> findByIncidentId(UUID incidentId);
}
