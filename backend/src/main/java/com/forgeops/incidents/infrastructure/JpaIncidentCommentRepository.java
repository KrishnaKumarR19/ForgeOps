package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.domain.IncidentComment;
import com.forgeops.incidents.domain.IncidentCommentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter for append-only incident comments (ADR-0030). Maps between the
 * framework-free {@link IncidentComment} and {@link IncidentCommentEntity}.
 */
@Repository
class JpaIncidentCommentRepository implements IncidentCommentRepository {

    private final SpringDataIncidentCommentJpaRepository jpa;

    JpaIncidentCommentRepository(SpringDataIncidentCommentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public IncidentComment save(IncidentComment c) {
        jpa.save(new IncidentCommentEntity(c.id(), c.incidentId(), c.authorId(),
                c.category(), c.body(), c.createdAt()));
        return c;
    }

    @Override
    public List<IncidentComment> findByIncidentId(UUID incidentId) {
        return jpa.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(JpaIncidentCommentRepository::toDomain)
                .toList();
    }

    private static IncidentComment toDomain(IncidentCommentEntity e) {
        return new IncidentComment(e.getId(), e.getIncidentId(), e.getAuthorId(),
                e.getCategory(), e.getBody(), e.getCreatedAt());
    }
}
