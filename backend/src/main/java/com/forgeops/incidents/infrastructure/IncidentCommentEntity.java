package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.CommentCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for an append-only incident comment (PERSISTENCE_MODEL.md §11), separate from the
 * {@link com.forgeops.incidents.domain.IncidentComment} domain record (ADR-0035). Mapped to
 * {@code incident_comments} ({@code V6}). All columns are insert-only (INV-INC-008).
 * Package-private.
 */
@Entity
@Table(name = "incident_comments")
class IncidentCommentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", updatable = false)
    private CommentCategory category;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IncidentCommentEntity() {
        // Required by JPA.
    }

    IncidentCommentEntity(UUID id, UUID incidentId, UUID authorId, CommentCategory category,
                          String body, Instant createdAt) {
        this.id = id;
        this.incidentId = incidentId;
        this.authorId = authorId;
        this.category = category;
        this.body = body;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getIncidentId() {
        return incidentId;
    }

    UUID getAuthorId() {
        return authorId;
    }

    CommentCategory getCategory() {
        return category;
    }

    String getBody() {
        return body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
