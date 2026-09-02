package com.forgeops.incidents.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An append-only investigation note/comment on an incident (PERSISTENCE_MODEL.md §11,
 * DOMAIN_MODEL.md §12, INV-INC-008). One comment type with an optional {@link CommentCategory};
 * content is immutable (no edit/delete). Framework-free (ADR-0030).
 *
 * @param id         comment id (UUID v7)
 * @param incidentId the incident this comment belongs to
 * @param authorId   the authoring user
 * @param category   optional category, or empty
 * @param body       comment content (required, non-blank)
 * @param createdAt  authorship time
 */
public record IncidentComment(
        UUID id,
        UUID incidentId,
        UUID authorId,
        CommentCategory category,
        String body,
        Instant createdAt) {

    public IncidentComment {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (incidentId == null) {
            throw new IllegalArgumentException("incidentId is required");
        }
        if (authorId == null) {
            throw new IllegalArgumentException("authorId is required");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }

    public Optional<CommentCategory> categoryValue() {
        return Optional.ofNullable(category);
    }
}
