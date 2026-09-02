package com.forgeops.events.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link OperationalEventEntity}. Package-private: the events
 * module's persistence internals are not visible outside {@code events.infrastructure}
 * (ModuleBoundaryTests). The domain-facing contract is {@link
 * com.forgeops.events.domain.OperationalEventRepository}, implemented by {@link
 * JpaOperationalEventRepository}.
 */
interface SpringDataOperationalEventJpaRepository extends JpaRepository<OperationalEventEntity, UUID> {

    Optional<OperationalEventEntity> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

    /**
     * Idempotent, conditional {@code RECEIVED → PROCESSED} transition (Phase 6 Slice 3,
     * INV-MSG-003, FR-RL-3/10). The {@code WHERE ... AND status = 'RECEIVED'} guard makes this
     * a single atomic step: a duplicate or concurrent delivery either transitions the row
     * exactly once (returns 1) or finds it already advanced (returns 0). Must run inside the
     * consumer's transaction; the commit precedes the message acknowledgement (INV-MSG-004).
     *
     * @return the number of rows updated: {@code 1} if this call performed the transition,
     *         {@code 0} if the row was not {@code RECEIVED} (already processed) or absent
     */
    @Modifying
    @Query(value = """
            UPDATE operational_events
            SET status = 'PROCESSED'
            WHERE id = :id AND status = 'RECEIVED'
            """, nativeQuery = true)
    int markProcessed(@Param("id") UUID id);
}
