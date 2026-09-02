package com.forgeops.incidents.domain;

/**
 * Optional categorization of an incident comment (DOMAIN_MODEL.md §12, PERSISTENCE_MODEL.md §11).
 * A single comment type with an optional category; absence means an uncategorized note.
 * Framework-free (ADR-0030).
 */
public enum CommentCategory {
    NOTE,
    INVESTIGATION,
    RESOLUTION
}
